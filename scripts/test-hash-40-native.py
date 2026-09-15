"""Hash40 native cache/source publication mechanisms; no embedding model calls.

Retains a new PostgreSQL schema and Milvus database, original fixtures, frozen
artifact migrations, helper/class hashes and all failures. Never clears data.
"""
from native_probe_runtime import java17_bin
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile

R = Path(__file__).resolve().parents[1]
JAVA = java17_bin()
p = argparse.ArgumentParser(); p.add_argument('--jar', type=Path, required=True); p.add_argument('--fixtures', type=Path, required=True)
p.add_argument('--tokenizer-manifest', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
p.add_argument('--normal-format-supplement', action='store_true', help='Disclosed extra development normal Raw flow, never main Hash40 or held-out success')
a = p.parse_args()
if a.output.exists() or a.output.with_suffix('.seed.sql').exists(): raise SystemExit('Fresh result required')
frozen = a.output.with_suffix('.frozen'); frozen.mkdir(parents=True)
source_name = 'RawSourceFormatVersionProbe' if a.normal_format_supplement else 'RawHashFortyProbe'
helper = frozen / (source_name + '.java'); helper.write_bytes((R / 'scripts' / (source_name + '.java')).read_bytes())
manifest = json.loads((a.fixtures / 'manifest.json').read_text())
for case in manifest['cases']:
    for phase in ['before', 'after']:
        original = case[phase]; data = (a.fixtures / original['path']).read_bytes()
        if len(data) != original['sizeBytes'] or hashlib.sha256(data).hexdigest() != original['sha256']: raise SystemExit('Frozen source changed: ' + case['id'])
tokenizer = json.loads(a.tokenizer_manifest.read_text())
assets = {name: hashlib.sha256((a.tokenizer_manifest.parent / name).read_bytes()).hexdigest() for name in tokenizer['files']}
if assets != tokenizer['files']: raise SystemExit('Frozen tokenizer asset mismatch')
namespace = 'raw_hash40_' + uuid.uuid4().hex[:12]
binding = {'artifact': str(a.jar.resolve()), 'jarSha256': hashlib.sha256(a.jar.read_bytes()).hexdigest(),
    'probeSourceSha256': hashlib.sha256(helper.read_bytes()).hexdigest(),
    'manifestSha256': hashlib.sha256((a.fixtures / 'manifest.json').read_bytes()).hexdigest(),
    'postgresSchema': namespace, 'milvusDatabase': namespace,
    'tokenizerAssetSha256': assets, 'modelRevision': tokenizer['modelRevision'], 'tokenizerRevision': tokenizer['tokenizerRevision'],
    'startedAt': datetime.now(timezone.utc).isoformat(), 'provenance': 'IMMUTABLE_JAR_CLASSES_AND_MIGRATIONS_NO_PRODUCTION_OVERLAY',
    'realModelInference': False, 'efficiencyClaim': False}
try:
    with zipfile.ZipFile(a.jar) as archive:
        migrations = {Path(name).name: archive.read(name) for name in archive.namelist() if name.startswith('BOOT-INF/classes/db/migration/V') and name.endswith('.sql')}
    sql = f'CREATE SCHEMA {namespace};SET search_path TO {namespace};\n' + ''.join(migrations[name].decode() for name in sorted(migrations, key=lambda name: int(name.split('__')[0][1:])))
    a.output.with_suffix('.seed.sql').write_text(sql)
    binding['migrationSha256'] = {name: hashlib.sha256(data).hexdigest() for name, data in migrations.items()}
    applied = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', '-'], input=sql, text=True, capture_output=True, timeout=90)
    a.output.with_suffix('.seed.log').write_text(applied.stdout + applied.stderr); applied.check_returncode()
    settings = dict(line.split('=', 1) for line in (R / '.env.target').read_text().splitlines() if line and not line.startswith('#'))
    env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'], PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema=' + namespace + '&stringtype=unspecified',
               PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'], PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
    with tempfile.TemporaryDirectory(prefix='evokb-hash40-native-') as directory:
        work = Path(directory); classes = work / 'classes'; classes.mkdir(); libraries = []
        hashes = {}
        with zipfile.ZipFile(a.jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    target = work / Path(name).name; data = archive.read(name); target.write_bytes(data); libraries.append(target); hashes[target.name] = hashlib.sha256(data).hexdigest()
        binding['dependencySha256'] = hashes
        cp = os.pathsep.join(str(path) for path in libraries)
        compiled = subprocess.run([str(JAVA / 'javac'), '-J-Xmx128m', '-J-XX:ActiveProcessorCount=2', '-cp', cp, '-d', str(classes), str(helper)], capture_output=True, text=True, timeout=90)
        a.output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr); compiled.check_returncode()
        binding['helperClassSha256'] = {str(path.relative_to(classes)): hashlib.sha256(path.read_bytes()).hexdigest() for path in classes.rglob('*.class')}
        ran = subprocess.run([str(JAVA / 'java'), '-Xmx256m', '-XX:ActiveProcessorCount=2', '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]', '-cp', str(classes) + os.pathsep + cp, 'cn.lgs.knowledgebase.application.raw.' + source_name, str(a.output), str(a.fixtures), str(a.tokenizer_manifest), namespace], capture_output=True, text=True, env=env, timeout=240)
        a.output.with_suffix('.runtime.log').write_text(ran.stdout + ran.stderr); binding['processExitCode'] = ran.returncode
except Exception as error: binding['failure'] = str(error)
report = json.loads(a.output.read_text()) if a.output.exists() else {'status': 'FAIL_HASH40_NATIVE_MECHANISMS', 'caseCount': 0, 'passedCases': 0}
report.update(binding); report['finishedAt'] = datetime.now(timezone.utc).isoformat()
if binding.get('processExitCode') != 0: report['status'] = 'FAIL_HASH40_NATIVE_MECHANISMS'
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
summary = {'status': report['status'], 'caseCount': report['caseCount'], 'passedCases': report['passedCases'],
    'passedDevelopment': sum(case['status'] == 'PASS' and case['split'] == 'development' for case in report.get('cases', [])),
    'passedHeldOut': sum(case['status'] == 'PASS' and case['split'] == 'held_out' for case in report.get('cases', [])),
    'failed': [{'id': case['caseId'], 'checks': [name for name, passed in case['checks'].items() if not passed], 'failure': case.get('failure')} for case in report.get('cases', []) if case['status'] != 'PASS'],
    'failure': binding.get('failure'), 'postgresSchema': namespace, 'milvusDatabase': namespace}
a.output.with_suffix('.summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)); print(json.dumps(summary, ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
