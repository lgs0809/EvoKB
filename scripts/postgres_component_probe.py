"""Shared retained-schema harness, binding evidence to the bytes actually compiled."""
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(output, *, source_name, prefix, extra_sql='', runtime_args=(), timeout=180, namespace_arg=False, jar_path=None, classes_path=None, source_migrations=False, additional_sources=()):
    output = Path(output)
    if output.exists() or output.with_suffix('.seed.sql').exists():
        raise ValueError('Use a fresh output path; preserve every attempt')
    settings = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                    if line and not line.startswith('#'))
    jar = Path(jar_path).resolve() if jar_path else ROOT / 'java/ekb-bootstrap/target/evokb.jar'
    jar_bytes = jar.read_bytes()
    source = ROOT / 'scripts' / source_name
    source_bytes = source.read_bytes()
    frozen_sources = {name: (ROOT / 'scripts' / name).read_bytes() for name in additional_sources}
    if any(Path(name).name != name or not name.endswith('.java') for name in frozen_sources):
        raise ValueError('Additional probe sources must be local Java basenames')
    namespace = prefix + uuid.uuid4().hex[:12]
    if source_migrations:
        migrations = {p.name: p.read_bytes() for p in
                      (ROOT / 'java/ekb-bootstrap/src/main/resources/db/migration').glob('V*.sql')}
    else:
        # A component run against a deployed artifact must not silently apply migrations
        # being edited for the next release in the shared working tree.
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
            migrations = {Path(name).name: archive.read(name) for name in archive.namelist()
                          if name.startswith('BOOT-INF/classes/db/migration/V') and name.endswith('.sql')}
    if not migrations:
        raise ValueError('The selected artifact contains no database migrations')
    seed = f'CREATE SCHEMA {namespace}; SET search_path TO {namespace};\n'
    seed += ''.join(migrations[name].decode() for name in
                    sorted(migrations, key=lambda name: int(name.split('__', 1)[0][1:])))
    for suffix, level in [('public', 10), ('secret', 30), ('other', 10)]:
        seed += f"""
INSERT INTO document(document_id,logical_name,status) VALUES('doc-{suffix}','Synthetic component fixture {suffix}','ACTIVE');
INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key)
VALUES('version-{suffix}','doc-{suffix}',1,'ACTIVE','component-fixture.md',0,'component-only','component-only');
INSERT INTO document_metadata(version_id,security_level) VALUES('version-{suffix}',{level});
INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash)
VALUES('block-{suffix}','version-{suffix}',1,'PARAGRAPH','Synthetic fixture source','component-only');
INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash)
VALUES('evidence-{suffix}','doc-{suffix}','version-{suffix}','block-{suffix}','component-only','{{}}','component-only');
"""
    seed += extra_sql
    output.parent.mkdir(parents=True, exist_ok=True)
    seed_path = output.with_suffix('.seed.sql')
    seed_path.write_text('-- Isolated component fixtures, not manufactured workflow results.\n' + seed)
    applied = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U',
                              'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1',
                              '--single-transaction', '-f', '-'], input=seed, text=True, capture_output=True)
    output.with_suffix('.seed.log').write_text(applied.stdout + applied.stderr)
    applied.check_returncode()
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin'
    with tempfile.TemporaryDirectory(prefix='evokb-component-probe-') as temp:
        work = Path(temp)
        # Both compiled classes and provenance use these frozen launch bytes.
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work / Path(name).name).write_bytes(archive.read(name))
        snapshot = work / source_name
        snapshot.write_bytes(source_bytes)
        for name, data in frozen_sources.items():
            (work / name).write_bytes(data)
        overlay = {}
        if classes_path:
            frozen = work / 'source-classes'
            frozen.mkdir()
            for original in Path(classes_path).rglob('*.class'):
                relative = original.relative_to(classes_path)
                data = original.read_bytes()
                target = frozen / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                overlay[str(relative)] = sha(data)
        cp = (str(work / 'source-classes') + os.pathsep if classes_path else '') + str(work / '*') + os.pathsep + str(work)
        compiled = subprocess.run([str(java / 'javac'), '-J-Xmx128m', '-cp', cp, '-d', str(work), str(snapshot),
                                   *(str(work / name) for name in frozen_sources)],
                                  capture_output=True, text=True)
        output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],
                   PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema=' + namespace + '&stringtype=unspecified')
        result = subprocess.run([str(java / 'java'), '-XX:ActiveProcessorCount=2', '-Xmx256m',
                                 '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                 '-cp', cp, source.stem, str(output.resolve()),
                                 *([namespace] if namespace_arg else []), *runtime_args],
                                env=env, capture_output=True, timeout=timeout)
        output.with_suffix('.runtime.log').write_bytes(result.stdout + result.stderr)
    report = json.loads(output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(), postgresSchema=namespace,
                  jarSha256=sha(jar_bytes), probeSourceSha256=sha(source_bytes),
                  seedSha256=sha(seed_path.read_bytes()), provenance='FROZEN_COMPILED_LAUNCH_BYTES',
                  currentJarStillMatchesLaunch=sha(jar.read_bytes()) == sha(jar_bytes),
                  migrationProvenance='EXPLICIT_WORKING_SOURCE_SNAPSHOT' if source_migrations else 'FROZEN_ARTIFACT_MIGRATIONS',
                  migrationSha256={name: sha(data) for name, data in migrations.items()})
    if frozen_sources:
        report['additionalProbeSourceSha256'] = {name: sha(data) for name, data in frozen_sources.items()}
    if classes_path:
        report.update(compiledSourceOverlay=overlay,
                      provenance='FROZEN_SOURCE_CLASS_OVERLAY_AND_DEPENDENCY_JAR_COMPONENT_ONLY',
                      deploymentClaim=False)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report.get('status'),
                          passed=sum(bool(v) for v in report.get('checks', {}).values()),
                          failed=[k for k,v in report.get('checks', {}).items() if not v],
                          postgresSchema=namespace, jarSha256=report['jarSha256'],
                          expiry=report.get('expiry'), output=str(output)), ensure_ascii=False))
    return result.returncode
