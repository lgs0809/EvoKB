#!/usr/bin/env python3
"""Use the actual built JDBC adapter in a new isolated local MySQL schema.

Creates marked queue fixtures using the repository's queue/claim/reset/complete
operations. Never updates application success, approval or knowledge rows.
No schema, container, volume or original failed result is deleted.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MYSQL = 'evokb-acceptance-mysql-1'


def mysql(statement, database=None):
    command = ['docker', 'exec', '-i', MYSQL, 'sh', '-c',
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -N -B --raw' +
               (' '+database if database else '')]
    return subprocess.check_output(command, input=statement.encode(), timeout=45)


def run(args):
    global MYSQL
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*',args.mysql_container) or not 1<=args.mysql_port<=65535:
        raise ValueError('Use a valid local MySQL container and port')
    MYSQL=args.mysql_container
    if not re.fullmatch(r'evokb_generation_probe_[a-z0-9_]{1,40}', args.schema):
        raise ValueError('Use a dedicated evokb_generation_probe_ schema')
    if args.output.exists():
        raise ValueError('Keep previous failures; choose a fresh report path')
    if mysql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+args.schema+"';").strip():
        raise ValueError('Use a new isolated schema; do not clear an existing database')
    jar = ROOT/'java/ekb-bootstrap/target/evokb.jar'
    built_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    mysql('CREATE DATABASE '+args.schema+' CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
    migrations = []
    for path in sorted((ROOT/'deploy/mysql/init').glob('*.sql')):
        mysql(path.read_text(), args.schema)
        migrations.append({'path':str(path.relative_to(ROOT)), 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    probe = 'GenerationTaskRetryProbe' if args.probe == 'retry' else 'GenerationTaskFenceProbe'
    source = ROOT/('scripts/'+probe+'.java')
    result = {'status':'FAIL_PROBE_EXECUTION', 'schema':args.schema, 'mysqlContainer':MYSQL,
              'mysqlPort':args.mysql_port,
              'jarSha256':built_hash, 'migrations':migrations, 'modelsCalled':0,
              'probeSourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
              'actualOperationTime':datetime.now(timezone.utc).isoformat()}
    password = subprocess.check_output(['docker','exec',MYSQL,'sh','-c','printf "%s" "$MYSQL_ROOT_PASSWORD"'],timeout=15).decode()
    with tempfile.TemporaryDirectory(prefix='evokb-task-fence-') as temporary:
        tmp = Path(temporary)
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (tmp/Path(name).name).write_bytes(archive.read(name))
        cp = str(tmp/'*')+os.pathsep+str(tmp)
        java = Path(subprocess.check_output(['/usr/libexec/java_home','-v','17']).decode().strip())/'bin'
        compiled = subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(tmp),str(source)],capture_output=True,text=True,timeout=40)
        if compiled.returncode:
            result['diagnostic']='PROBE_COMPILE_FAILURE';result['compileDiagnostic']=compiled.stderr[-2000:]
        else:
            raw_result = tmp/'result.json'
            env = dict(os.environ,EKB_PROBE_JDBC='jdbc:mysql://127.0.0.1:'+str(args.mysql_port)+'/'+args.schema+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',EKB_PROBE_PASSWORD=password)
            completed = subprocess.run([str(java/'java'),'-Xmx192m','-cp',cp,probe,str(raw_result)],env=env,capture_output=True,text=True,timeout=90)
            result['returnCode']=completed.returncode
            if raw_result.exists(): result.update(json.loads(raw_result.read_text()))
            else: result['diagnostic']='PROBE_EXECUTION_FAILURE';result['failureLog']=completed.stderr[-2000:]
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    args.output.with_suffix('.sql').write_text('-- Read-only synthetic queue fixture observation; does not assert model or application acceptance.\nSELECT task_id, version_id, task_type, subject_id, status, attempt, output_hash, error_code, next_attempt_at FROM compile_task ORDER BY created_at, task_id;\nSELECT task_id, attempt, outcome, error_code, occurred_at, next_attempt_at FROM compile_task_failure ORDER BY task_id, attempt;\n')
    print(json.dumps({'status':result['status'],'schema':args.schema,'modelsCalled':0,'cases':{k:v['passed'] for k,v in result.get('cases',{}).items()}}))
    if not result['status'].startswith('PASS'): raise SystemExit(1)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--schema',required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--mysql-container',default='evokb-acceptance-mysql-1')
    p.add_argument('--mysql-port',type=int,default=13365)
    p.add_argument('--probe', choices=['fence','retry'], default='fence')
    run(p.parse_args())
