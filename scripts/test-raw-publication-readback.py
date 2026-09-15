"""Read-only atomic Raw proof; actual publication IDs and resident model identity, never inference."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import uuid

from native_probe_runtime import FrozenBootArtifact, java17_bin

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_settings():
    return dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                if line and not line.startswith('#'))


def parser_ready(url):
    if not url.startswith('http://127.0.0.1:'):
        raise ValueError('Only the actual isolated loopback parser is accepted')
    with urllib.request.urlopen(url.rstrip('/') + '/ready', timeout=5) as response:
        value = json.load(response)
    if value.get('status') != 'ready':
        raise ValueError('Actual parser is not ready')
    return value


def actual_redis(file, stream, group):
    message = file['message_id']
    if not message or len(message.split('-')) != 2 or not all(x.isdigit() for x in message.split('-')):
        raise ValueError('Exact durable Redis delivery message ID required')
    def query(*parts):
        result = subprocess.run(['docker', 'exec', 'evokb-target-redis-1', 'redis-cli', '--json',
                                 *parts], capture_output=True, text=True, timeout=10)
        result.check_returncode()
        return json.loads(result.stdout)
    retained = query('XRANGE', stream, message, message)
    pending = query('XPENDING', stream, group, message, message, '1')
    body = dict(zip(retained[0][1][::2], retained[0][1][1::2])) if len(retained) == 1 else {}
    checks = {
        'actualOriginalDeliveryRetainedExactlyOnce': len(retained) == 1 and retained[0][0] == message
            and body == {'file_id': file['file_id']} and file['exact_delivery_count'] == 1,
        'actualTerminalDeliveryNoLongerPending': pending == [] and file['status'] == 'SUCCEEDED',
    }
    return dict(fileId=file['file_id'], exactMessageId=message, retained=retained,
                pending=pending, stream=stream, consumerGroup=group, checks=checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--logical-name', required=True)
    parser.add_argument('--parser-name', default='mineru-hybrid-engine')
    parser.add_argument('--parser-version', default='3.4.5',
                        help='Exact expected parser version, e.g. local-text / 2; never inferred from DB')
    parser.add_argument('--require-current-checkpoint', action='store_true',
                        help='Require a real current V23 checkpoint; legacy unknown sources are not certified')
    parser.add_argument('--version-id', help='Exact actual normal source version, not an invented ID')
    parser.add_argument('--file-id', help='Exact browser/API Raw file; assert its producer/runtime and Redis ACK')
    parser.add_argument('--original', type=Path, help='Unchanged actual original bytes for independent hash/size')
    parser.add_argument('--parser-ready-url', help='Optional actual current frozen runtime binding')
    parser.add_argument('--probe-source', type=Path, help='Preserved old diagnostic helper for real RED; no production overlay')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for value in [args.version_id, args.file_id]:
        if value:
            uuid.UUID(value)
    if args.file_id and not args.version_id:
        raise ValueError('A normal file proof must bind its exact version')
    output = args.output.resolve()
    work = output.with_suffix('.frozen')
    if output.exists() or work.exists() or any(output.with_suffix(x).exists()
            for x in ['.compile.log', '.runtime.log', '.parser-ready-before.json', '.parser-ready-after.json']):
        raise ValueError('Fresh evidence path required; preserve old failure reports')
    output.parent.mkdir(parents=True, exist_ok=True)
    state_path = ROOT / '.tmp/target/process.json'
    before = json.loads(state_path.read_text())
    artifact = FrozenBootArtifact(before['artifact'], before['jarSha256'])
    work.mkdir()
    classpath = artifact.extract(work)
    source_path = args.probe_source or ROOT / 'scripts/RawPublicationReadbackProbe.java'
    source = source_path.read_bytes()
    frozen = work / 'RawPublicationReadbackProbe.java'
    frozen.write_bytes(source)
    classes = work / 'classes'
    classes.mkdir()
    ready_path = output.with_suffix('.parser-ready-before.json')
    ready_before = parser_ready(args.parser_ready_url) if args.parser_ready_url else None
    if ready_before is not None:
        ready_path.write_text(json.dumps(ready_before, ensure_ascii=False, indent=2))
    original = args.original.read_bytes() if args.original else None
    provenance = dict(deployedObserver=before, actualImmutableJarSha256=artifact.sha256,
                     sourcePath=str(source_path), probeSourceSha256=digest(source),
                     wrapperSha256=digest(Path(__file__).read_bytes()),
                     expectedVersionId=args.version_id, expectedNormalFileId=args.file_id,
                     expectedParserName=args.parser_name, expectedParserVersion=args.parser_version,
                     requireCurrentCheckpoint=args.require_current_checkpoint,
                     unchangedOriginalSha256=digest(original) if original is not None else None,
                     originalSizeBytes=len(original) if original is not None else None,
                     diagnosticLegacySource=args.probe_source is not None,
                     scriptLibraryHashes=classpath.library_hashes)
    java = java17_bin()
    result = subprocess.run([str(java/'javac'), '-J-Xmx128m', '-J-XX:ActiveProcessorCount=2',
                             '-cp', classpath.value, '-d', str(classes), str(frozen)],
                            capture_output=True, text=True, timeout=30)
    output.with_suffix('.compile.log').write_text(result.stdout + result.stderr)
    if result.returncode:
        output.write_text(json.dumps(dict(status='FAIL_READONLY_PROBE_COMPILE', **provenance), indent=2))
        return 1
    settings = read_settings()
    env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],
               PROBE_DB_USER=settings['EKB_DB_USER'], PROBE_JDBC_URL=settings['EKB_JDBC_URL'],
               PROBE_MINIO_URL=settings['EKB_MINIO_PUBLIC_ENDPOINT'],
               PROBE_MINIO_BUCKET=settings['EKB_MINIO_BUCKET'],
               PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],
               PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'],
               PROBE_MILVUS_URL=settings['EKB_MILVUS_URL'],
               PROBE_RUNTIME_STARTED_AT=before['startedAt'],
               PROBE_REQUIRE_CURRENT_CHECKPOINT=str(args.require_current_checkpoint).lower(),
               PROBE_CURRENT_PARSER_SETTINGS=json.dumps({key: value for key, value in settings.items()
                                                       if key.startswith('EKB_MINERU_')}))
    result = subprocess.run([str(java/'java'), '-Xmx256m', '-XX:ActiveProcessorCount=2',
                             '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                             '-cp', str(classes)+os.pathsep+classpath.value,
                             'cn.lgs.knowledgebase.application.raw.RawPublicationReadbackProbe',
                             str(output), args.logical_name, settings['EKB_EMBED_TOKENIZER_MANIFEST'],
                             args.parser_name, args.version_id or '', args.file_id or '',
                             digest(original) if original is not None else '',
                             str(len(original)) if original is not None else '',
                             str(ready_path) if ready_before is not None else '', args.parser_version],
                            env=env, capture_output=True, text=True, timeout=75)
    output.with_suffix('.runtime.log').write_text(result.stdout + result.stderr)
    report = json.loads(output.read_text()) if output.exists() else dict(status='FAIL_READONLY_PROBE_RUNTIME', checks={})
    report.update(provenance, actualReadbackAt=datetime.now(timezone.utc).isoformat())
    checks = report.setdefault('checks', {})
    after = json.loads(state_path.read_text())
    checks['actualImmutableObserverUnchanged'] = artifact.unchanged() and all(
        before.get(k) == after.get(k) for k in ['jarSha256', 'pid', 'startedAt', 'artifact'])
    checks['actualDiagnosticSourceFrozen'] = source_path.read_bytes() == source
    if ready_before is not None:
        ready_after = parser_ready(args.parser_ready_url)
        output.with_suffix('.parser-ready-after.json').write_text(json.dumps(ready_after, indent=2))
        checks['actualCurrentParserFrozenBundleUnchanged'] = all(ready_before.get(k) == ready_after.get(k)
            for k in ['parserSourceSha256', 'parserRuntimeBundleSha256', 'parserRuntimeModulesSha256',
                      'stageSeconds', 'cpuThreads', 'hybridManifest', 'effectiveSite'])
    if args.file_id and not args.probe_source:
        matches = [f for f in report.get('deliveryMetadata', []) if f['file_id'] == args.file_id]
        checks['exactNormalRawDeliveryMetadataExists'] = len(matches) == 1
        if len(matches) == 1:
            redis = actual_redis(matches[0], 'evokb:target:raw:v1', 'raw-ingestion')
            report['nativeRedisReadback'] = redis
            checks.update(redis['checks'])
    # Preserve all Java/core failures even if observation identities succeeded.
    passed = report['status'].startswith('PASS') and result.returncode == 0 and all(checks.values())
    report['status'] = 'PASS_ACTUAL_RAW_ENCODING_READBACK' if passed else 'FAIL_ACTUAL_RAW_ENCODING_READBACK'
    report['boundary'] = 'No source/cache/profile/task/Redis mutation, no model load or encode. Actual artifact, exact atomic input/vector and publication authority; business Gold, access HTTP and UI are independently required.'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(dict(status=report['status'], checks=checks, failure=report.get('failure')), ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
