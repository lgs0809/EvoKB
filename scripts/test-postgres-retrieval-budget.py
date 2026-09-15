#!/usr/bin/env python3
"""Retained PostgreSQL component fixtures: budget upgrade, concurrency and actual process crash."""
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
        raise ValueError('Use a fresh output to preserve every attempt')
    settings = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                    if line and not line.startswith('#'))
    namespace = 'evokb_budget_probe_' + uuid.uuid4().hex[:12]
    migration_dir = ROOT / 'java/ekb-bootstrap/src/main/resources/db/migration'
    migrations = sorted(migration_dir.glob('V*.sql'), key=lambda p: int(p.name.split('__', 1)[0][1:]))
    seed = f'CREATE SCHEMA {namespace}; SET search_path TO {namespace};\n'
    seed += ''.join(p.read_text() for p in migrations if int(p.name.split('__', 1)[0][1:]) < 6)
    seed += """
-- Legacy component records inserted before the new migration, never production query statuses.
INSERT INTO query_session(session_id,user_id,name,original_question,filters_json,status,choices_json,citation_evidence_ids)
VALUES('budget-legacy','budget-component-user','legacy component','旧版预算升级','{}','RUNNING','[]','[]');
INSERT INTO retrieval_round(round_id,session_id,round_no,input_queries,decision,missing_information,next_queries,started_at,finished_at)
VALUES('legacy-round','budget-legacy',7,'["旧检索"]','CONTINUE_SEARCH','["缺少付款资料"]','["最后线索"]',clock_timestamp(),clock_timestamp());
"""
    seed += ''.join(p.read_text() for p in migrations if int(p.name.split('__', 1)[0][1:]) >= 6)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    seed_path = args.output.with_suffix('.seed.sql')
    seed_path.write_text(seed)
    applied = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U',
                              'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1',
                              '--single-transaction', '-f', '-'], input=seed, text=True, capture_output=True)
    args.output.with_suffix('.seed.log').write_text(applied.stdout + applied.stderr)
    applied.check_returncode()
    jar = ROOT / 'java/ekb-bootstrap/target/evokb.jar'
    source = ROOT / 'scripts/RetrievalBudgetProbe.java'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin'
    with tempfile.TemporaryDirectory(prefix='evokb-budget-probe-') as temp:
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
        command = [str(java / 'java'), '-Xmx256m',
                   '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]', '-cp', cp, 'RetrievalBudgetProbe']
        crash_path = args.output.with_suffix('.crash.json')
        crash = subprocess.run(command + ['crash-after-reservation', str(crash_path.resolve())],
                               env=env, capture_output=True, timeout=60)
        args.output.with_suffix('.crash.log').write_bytes(crash.stdout + crash.stderr)
        if crash.returncode != 23 or not crash_path.exists():
            raise RuntimeError('The controlled component JVM did not reach its abrupt-exit checkpoint')
        result = subprocess.run(command + ['verify-after-process-restart', str(args.output.resolve())],
                                env=env, capture_output=True, timeout=180)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout + result.stderr)
    report = json.loads(args.output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(), postgresSchema=namespace,
                  intentionalCrashExitCode=crash.returncode,
                  jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  probeSourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  seedSha256=hashlib.sha256(seed_path.read_bytes()).hexdigest(),
                  crashCheckpoint=json.loads(crash_path.read_text()))
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'passed': sum(report['checks'].values()),
                      'failed': [k for k,v in report['checks'].items() if not v], 'postgresSchema': namespace}))
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
