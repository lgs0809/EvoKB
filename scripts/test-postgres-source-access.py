#!/usr/bin/env python3
"""Verify the compiled access adapter in a fresh retained PostgreSQL schema; never reset business data."""
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

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Use a fresh result path to preserve every attempt')
    settings = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                    if line and not line.startswith('#'))
    namespace = 'evokb_access_probe_' + uuid.uuid4().hex[:12]
    migrations = ROOT / 'java/ekb-bootstrap/src/main/resources/db/migration'
    seed = f'CREATE SCHEMA {namespace}; SET search_path TO {namespace};\n'
    seed += ''.join(p.read_text() for p in sorted(migrations.glob('V*.sql'), key=lambda p: int(p.name.split('__', 1)[0][1:])))
    for suffix, level in [('public', 10), ('secret', 30), ('other', 10)]:
        seed += f"""
INSERT INTO document(document_id,logical_name,status) VALUES('doc-{suffix}','SourceAccess component fixture {suffix}','ACTIVE');
INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key)
VALUES('version-{suffix}','doc-{suffix}',1,'ACTIVE','component-fixture.md',0,'component-only','component-only');
INSERT INTO document_metadata(version_id,security_level) VALUES('version-{suffix}',{level});
INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash)
VALUES('block-{suffix}','version-{suffix}',1,'PARAGRAPH','SourceAccess fixture source','component-only');
INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash)
VALUES('evidence-{suffix}','doc-{suffix}','version-{suffix}','block-{suffix}','component-only','{{}}','component-only');
"""
    seed += """
INSERT INTO document_unit(unit_id,version_id,unit_no) VALUES('unit-public','version-public',1),('unit-secret','version-secret',1);
INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle)
VALUES('summary-public','unit-public','version-public','component-only','component-only','PASS','PASS','ACTIVE'),
('summary-secret','unit-secret','version-secret','component-only','component-only','PASS','PASS','ACTIVE');
INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status) VALUES('fact-public','summary-public','公开条款','公开事实','PASS');
"""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    seed_path = args.output.with_suffix('.seed.sql')
    seed_path.write_text('-- Component fixtures, not manufactured workflow results.\n' + seed)
    applied = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U',
                              'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1',
                              '--single-transaction', '-f', '-'], input=seed, text=True, capture_output=True)
    args.output.with_suffix('.seed.log').write_text(applied.stdout + applied.stderr)
    applied.check_returncode()
    jar = ROOT / 'java/ekb-bootstrap/target/evokb.jar'
    source = ROOT / 'scripts/SourceAccessProbe.java'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin'
    with tempfile.TemporaryDirectory(prefix='evokb-access-probe-') as temp:
        work = Path(temp)
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work / Path(name).name).write_bytes(archive.read(name))
        cp = str(work / '*') + os.pathsep + str(work)
        compiled = subprocess.run([str(java / 'javac'), '-cp', cp, '-d', str(work), str(source)], capture_output=True, text=True)
        args.output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],
                   PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema=' + namespace + '&stringtype=unspecified')
        result = subprocess.run([str(java / 'java'), '-Xmx256m',
                                 '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                 '-cp', cp, 'SourceAccessProbe', str(args.output.resolve())],
                                env=env, capture_output=True, timeout=180)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout + result.stderr)
    report = json.loads(args.output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(), postgresSchema=namespace,
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  probeSourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  seedSha256=hashlib.sha256(seed_path.read_bytes()).hexdigest())
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
