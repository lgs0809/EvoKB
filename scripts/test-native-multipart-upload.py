"""Frozen new upload sources with pinned deployed dependency JAR; real retained PG/MinIO.

This is a source-overlay component probe. It cannot certify the deployed HTTP/UI wiring.
The 600-second signed URL test waits real wall time without changing storage/server clocks.
"""
from native_probe_runtime import java17_bin
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import zipfile

R = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--output', required=True, type=Path)
p.add_argument('--jar-path', type=Path, required=True)
p.add_argument('--compile-only', action='store_true')
p.add_argument('--skip-signature-expiry', action='store_true')
a = p.parse_args()
if a.output.exists() or a.output.with_suffix('.frozen').exists():
    raise SystemExit('Fresh evidence path required; retain all failures')
a.output.parent.mkdir(parents=True, exist_ok=True)
work = a.output.with_suffix('.frozen'); work.mkdir()
sources = [*sorted((R/'java/ekb-domain/src/main/java').rglob('*.java')),
           *sorted((R/'java/ekb-application/src/main/java').rglob('*.java')),
           *sorted((R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/upload').glob('*.java')),
           R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcIngestionRepository.java',
           R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/JdbcRawPublicationWriter.java',
           R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/ingestion/DurableIngestionWorker.java',
           R/'java/ekb-interfaces/src/main/java/cn/lgs/knowledgebase/interfaces/http/UploadSessionController.java',
           R/'scripts/NativeMultipartUploadProbe.java']
snapshots = {str(x.relative_to(R)): x.read_bytes() for x in sources}
jar = a.jar_path.read_bytes()
libs = []
with zipfile.ZipFile(io.BytesIO(jar)) as archive:
    for name in archive.namelist():
        if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
            target = work/'lib'/Path(name).name
            target.parent.mkdir(exist_ok=True); target.write_bytes(archive.read(name)); libs.append(target)
frozen = []
for name, data in snapshots.items():
    target = work/'src'/name; target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data); frozen.append(target)
java = java17_bin()
cp = os.pathsep.join(str(x) for x in libs)
classes = work/'classes'; classes.mkdir()
started = time.monotonic()
compiled = subprocess.run([str(java/'javac'), '-J-Xmx256m', '-J-XX:ActiveProcessorCount=2',
                           '-cp', cp, '-d', str(classes), *(str(x) for x in frozen)], capture_output=True, text=True)
a.output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr)
provenance = dict(startedAt=datetime.now(timezone.utc).isoformat(),
                  sourceHashes={name: hashlib.sha256(data).hexdigest() for name, data in snapshots.items()},
                  dependencyJarSha256=hashlib.sha256(jar).hexdigest(),
                  compileElapsedSeconds=time.monotonic()-started,
                  provenance='FROZEN_SOURCE_OVERLAY_EXPLICIT_SOURCE_MIGRATIONS_AND_IMMUTABLE_DEPENDENCY_JAR', deploymentClaim=False)
if compiled.returncode:
    a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_COMPILE', **provenance), indent=2))
    raise SystemExit('Actual compile failed; retained log')
if a.compile_only:
    a.output.write_text(json.dumps(dict(status='PASS_NATIVE_LIMITED_SOURCE_COMPILE', **provenance), indent=2))
    print(json.dumps(dict(status='PASS_NATIVE_LIMITED_SOURCE_COMPILE', seconds=provenance['compileElapsedSeconds'])))
    raise SystemExit(0)
settings = dict(line.split('=', 1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
namespace = 'acceptance_multipart_'+uuid.uuid4().hex[:12]
migrations = sorted((x for x in (R/'java/ekb-bootstrap/src/main/resources/db/migration').glob('V*.sql')
                     if int(x.name.split('__')[0][1:]) <= 18), key=lambda x:int(x.name.split('__')[0][1:]))
migration_data = {x.name:x.read_bytes() for x in migrations}
seed = 'CREATE SCHEMA '+namespace+';SET search_path TO '+namespace+';\n'+''.join(data.decode() for data in migration_data.values())
a.output.with_suffix('.seed.sql').write_text(seed)
seeded = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U', 'evokb_target', '-d',
                         'evokb_target', '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', '-'], input=seed, capture_output=True, text=True)
a.output.with_suffix('.seed.log').write_text(seeded.stdout+seeded.stderr)
provenance.update(postgresSchema=namespace, migrationHashes={name:hashlib.sha256(data).hexdigest() for name,data in migration_data.items()},
                  seedSha256=hashlib.sha256(seed.encode()).hexdigest())
if seeded.returncode:
    a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_SCHEMA', **provenance), indent=2))
    raise SystemExit('Actual migration failed; retained log')
env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],
           PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema='+namespace+'&stringtype=unspecified',
           PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'], PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
try:
    result = subprocess.run([str(java/'java'), '-Xmx256m', '-XX:ActiveProcessorCount=2',
                             '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]', '-cp', str(classes)+os.pathsep+cp,
                             'NativeMultipartUploadProbe', str(a.output.resolve()), str(work/'originals'), namespace, *(['skip-signature-expiry'] if a.skip_signature_expiry else [])],
                            env=env, capture_output=True, timeout=720)
    a.output.with_suffix('.runtime.log').write_bytes(result.stdout+result.stderr)
except subprocess.TimeoutExpired as failure:
    a.output.with_suffix('.runtime.log').write_bytes((failure.stdout or b'')+(failure.stderr or b''))
    old = json.loads(a.output.read_text()) if a.output.exists() else {}
    a.output.write_text(json.dumps(dict(old, status='FAIL_NATIVE_RUNTIME_TIMEOUT', **provenance), indent=2))
    raise SystemExit('Actual 720-second probe timeout; preserve partial facts')
if not a.output.exists():
    a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_RUNTIME_NO_RESULT', **provenance), indent=2))
    raise SystemExit('Native runtime exited without result; retained log')
report = json.loads(a.output.read_text()); report.update(provenance)
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(dict(status=report['status'], passed=sum(bool(x) for x in report.get('checks', {}).values()),
                     failed=[k for k,v in report.get('checks', {}).items() if not v], output=str(a.output), postgresSchema=namespace), ensure_ascii=False))
raise SystemExit(result.returncode)
