#!/usr/bin/env python3
"""Verify actual grounded assets in the deployed PG/Milvus/MinIO acceptance environment, read-only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--summary', required=True)
    p.add_argument('--publication-readback', action='store_true', help='Inspect arbitrary current revisions without fixture-specific output counts')
    args = p.parse_args()
    if args.output.exists():
        raise ValueError('Keep every result; use a fresh path')
    settings = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                    if line and not line.startswith('#'))
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    jar = Path(state['artifact'])
    class_name = 'GeneratedPublicationReadbackProbe' if args.publication_readback else 'AssetRuntimeProbe'
    source = ROOT / 'scripts' / (class_name + '.java')
    source_bytes = source.read_bytes()
    jar_bytes = jar.read_bytes()
    if hashlib.sha256(jar_bytes).hexdigest() != state['jarSha256']:
        raise ValueError('Probe must run against the actually deployed artifact')
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='evokb-runtime-probe-') as temp:
        work = Path(temp)
        frozen_jar = work / 'deployed.jar'
        frozen_jar.write_bytes(jar_bytes)
        frozen_source = work / source.name
        frozen_source.write_bytes(source_bytes)
        args.output.with_suffix('.probe.java').write_bytes(source_bytes)
        with zipfile.ZipFile(frozen_jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work / Path(name).name).write_bytes(archive.read(name))
        cp = str(work / '*') + os.pathsep + str(work)
        compiled = subprocess.run([str(java / 'javac'), '-cp', cp, '-d', str(work), str(frozen_source)], capture_output=True, text=True, timeout=60)
        args.output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],
                   PROBE_JDBC_URL=settings['EKB_JDBC_URL'], PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],
                   PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
        result = subprocess.run([str(java / 'java'), '-Xmx256m',
                                 '-XX:ActiveProcessorCount=2',
                                 '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                 '-cp', cp, class_name, str(args.output.resolve()), args.summary],
                                env=env, capture_output=True, timeout=180)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout + result.stderr)
    report = json.loads(args.output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(), jarSha256=state['jarSha256'],
                  probeSourceSha256=hashlib.sha256(source_bytes).hexdigest(),
                  inspectionArtifact=state['artifact'])
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], jarSha256=report['jarSha256'],
                          passed=sum(bool(v) for v in report.get('checks', {}).values()),
                          failed=[k for k,v in report.get('checks', {}).items() if not v],
                          output=str(args.output)), ensure_ascii=False))
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
