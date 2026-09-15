#!/usr/bin/env python3
"""Verify download receipts, source attribution and replay in fresh retained PostgreSQL fixtures."""
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
    namespace = 'evokb_usage_probe_' + uuid.uuid4().hex[:12]
    migrations = ROOT / 'java/ekb-bootstrap/src/main/resources/db/migration'
    seed = f'CREATE SCHEMA {namespace}; SET search_path TO {namespace};\n'
    seed += ''.join(p.read_text() for p in sorted(migrations.glob('V*.sql'), key=lambda p: int(p.name.split('__', 1)[0][1:])))
    for suffix, level in [('a', 10), ('b', 10), ('private', 30)]:
        seed += f"""
INSERT INTO document(document_id,logical_name,status) VALUES('doc-{suffix}','Download usage component fixture {suffix}','ACTIVE');
INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key)
VALUES('version-{suffix}','doc-{suffix}',1,'ACTIVE','component.md',9,'component-only','component-only');
INSERT INTO document_metadata(version_id,security_level) VALUES('version-{suffix}',{level});
INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash)
VALUES('block-{suffix}','version-{suffix}',1,'PARAGRAPH','Synthetic component source','component-only');
INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash)
VALUES('e-{suffix}','doc-{suffix}','version-{suffix}','block-{suffix}','component-only','{{}}','component-only');
"""
    seed += """
INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash)
VALUES('e-a2','doc-a','version-a','block-a','component-only','{}','component-only');
INSERT INTO enterprise_user(user_id,username,display_name,password_hash)
VALUES('usage-u','usage_component','Usage component','not-a-login-hash'),('usage-other','usage_other_component','Other component','not-a-login-hash');
"""
    for key, user, status, citations in [('q1','usage-u','ANSWERED',['e-a','e-a2','e-b']),('q2','usage-u','ANSWERED',['e-a']),('q3','usage-u','INSUFFICIENT',['e-b']),('foreign','usage-other','ANSWERED',['e-a']),('running','usage-u','RUNNING',['e-a']),('private','usage-u','ANSWERED',['e-a'])]:
        seed += f"INSERT INTO query_session(session_id,user_id,name,original_question,filters_json,status,choices_json,citation_evidence_ids) VALUES('usage-{key}','{user}','component','component-only question','{{}}','{status}','[]','{json.dumps(citations)}');\n"
    seed += "INSERT INTO query_source_dependency(session_id,version_id) VALUES('usage-private','version-private');\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    seed_path = args.output.with_suffix('.seed.sql')
    seed_path.write_text('-- Component fixtures, not manufactured workflow results.\n' + seed)
    applied = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U',
                              'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1',
                              '--single-transaction', '-f', '-'], input=seed, text=True, capture_output=True)
    args.output.with_suffix('.seed.log').write_text(applied.stdout + applied.stderr)
    applied.check_returncode()
    jar = ROOT / 'java/ekb-bootstrap/target/evokb.jar'
    source = ROOT / 'scripts/DownloadUsageProbe.java'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin'
    with tempfile.TemporaryDirectory(prefix='evokb-usage-probe-') as temp:
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
                                 '-cp', cp, 'DownloadUsageProbe', str(args.output.resolve())],
                                env=env, capture_output=True, timeout=180)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout + result.stderr)
        if result.returncode == 0:
            restart_path = args.output.with_suffix('.restart.json')
            replay = subprocess.run([str(java / 'java'), '-Xmx256m', '-cp', cp,
                                     '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                     'DownloadUsageProbe', str(restart_path.resolve()), 'replay-new-jvm'],
                                    env=env, capture_output=True, timeout=60)
            args.output.with_suffix('.restart.log').write_bytes(replay.stdout + replay.stderr)

    report = json.loads(args.output.read_text())
    report['restartChecks'] = json.loads(args.output.with_suffix('.restart.json').read_text()) if result.returncode == 0 else {}
    if result.returncode == 0 and replay.returncode != 0:
        report['status'] = 'FAIL_REAL_POSTGRES_DOWNLOAD_USAGE_RESTART'
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(), postgresSchema=namespace,
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  probeSourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  seedSha256=hashlib.sha256(seed_path.read_bytes()).hexdigest())
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))
    raise SystemExit(0 if report['status'].startswith('PASS') else 1)


if __name__ == '__main__':
    main()
