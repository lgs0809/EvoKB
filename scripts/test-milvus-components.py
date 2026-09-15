#!/usr/bin/env python3
"""Run the compiled application adapter against actual Milvus, PostgreSQL and local Qwen.

Each invocation retains a separate PostgreSQL schema and Milvus database. No business
database, collection, object or volume is overwritten or deleted. Output excludes secrets.
"""
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
        raise ValueError('Keep every attempt; choose a fresh output path')
    settings = {}
    for line in (ROOT / '.env.target').read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            settings[key] = value.strip().strip('"').strip("'")
    namespace = 'evokb_milvus_probe_' + uuid.uuid4().hex[:12]
    jar = ROOT / 'java/ekb-bootstrap/target/evokb.jar'
    source = ROOT / 'scripts/MilvusIndexProbe.java'
    migration = ROOT / 'java/ekb-bootstrap/src/main/resources/db/migration'
    seed = f'CREATE SCHEMA {namespace}; SET search_path TO {namespace};\n' + ''.join(p.read_text() for p in sorted(migration.glob('V*.sql')))
    for suffix, level in [('public', 10), ('secret', 30), ('other', 10)]:
        seed += f"""
INSERT INTO document(document_id,logical_name,status) VALUES('doc-{suffix}','Milvus component fixture {suffix}','ACTIVE');
INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key)
VALUES('version-{suffix}','doc-{suffix}',1,'ACTIVE','component-fixture.md',0,'component-test-only','component-test-only');
INSERT INTO document_metadata(version_id,source_department,material_category,security_level)
VALUES('version-{suffix}','法务','合同',{level});
"""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    seed_path = args.output.with_suffix('.seed.sql')
    seed_path.write_text('-- Component fixtures, not manufactured workflow results.\n' + seed)
    applied = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql',
                              '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1',
                              '--single-transaction', '-f', '-'], input=seed, text=True, capture_output=True)
    applied.check_returncode()
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin'
    with tempfile.TemporaryDirectory(prefix='evokb-milvus-probe-') as temp:
        work = Path(temp)
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work / Path(name).name).write_bytes(archive.read(name))
        cp = str(work / '*') + os.pathsep + str(work)
        compiled = subprocess.run([str(java / 'javac'), '-cp', cp, '-d', str(work), str(source)], capture_output=True, text=True)
        if compiled.returncode:
            args.output.with_suffix('.compile.log').write_text(compiled.stderr)
            compiled.check_returncode()
        env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],
                   PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema=' + namespace + '&stringtype=unspecified')
        # macOS may supply a SOCKS bypass list omitting numeric loopback. Keep local dependencies local.
        result = subprocess.run([str(java / 'java'), '-Xmx256m',
                                 '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                 '-cp', cp, 'MilvusIndexProbe',
                                 str(args.output.resolve()), namespace], capture_output=True, timeout=600, env=env)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout + result.stderr)
    report = json.loads(args.output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(), postgresSchema=namespace,
                  milvusDatabase=namespace, collection='knowledge_chunks',
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  probeSourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  seedSha256=hashlib.sha256(seed_path.read_bytes()).hexdigest())
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
