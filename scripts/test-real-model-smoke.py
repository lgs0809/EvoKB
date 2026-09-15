#!/usr/bin/env python3
"""Enable the repository's real model test in a fresh isolated MySQL schema.

The application acceptance database is never used by this secondary Spring
context. Credentials stay in the environment, not the evidence files.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]


def run(args):
    if not re.fullmatch(r'evokb_model_smoke_[a-z0-9_]{1,40}',args.schema):raise ValueError('Use a dedicated model smoke schema')
    if args.output.exists() or args.output.with_suffix('.log').exists():raise ValueError('Keep prior observations')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ,**dict(line.split('=',1) for line in (ROOT/'.env.acceptance').read_text().splitlines() if line))
    if env['EKB_SMALL_MODEL']!='gpt-5.6-luna' or env['EKB_LARGE_MODEL']!='gpt-5.6-terra':raise ValueError('Preserve Luna/Terra roles')
    command=['docker','exec','-i','evokb-acceptance-mysql-1','sh','-c',
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -N -B --raw']
    present=subprocess.check_output(command,input=("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+args.schema+"';").encode())
    if present.strip():raise ValueError('Never clear or reuse an existing schema')
    create='CREATE DATABASE '+args.schema+' CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'
    subprocess.run(command,input=create.encode(),check=True)
    migrations=[]
    for path in sorted((ROOT/'deploy/mysql/init').glob('*.sql')):
        subprocess.run(command[:-1]+[command[-1]+' '+args.schema],input=path.read_bytes(),check=True)
        migrations.append({'path':str(path.relative_to(ROOT)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    env.update(EKB_REAL_MODEL_SMOKE='true',EKB_JDBC_URL='jdbc:mysql://127.0.0.1:13365/'+args.schema+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
               EKB_MYSQL_USER='root',EKB_MYSQL_PASSWORD=env['EKB_MYSQL_ROOT_PASSWORD'],EKB_MINIO_ENDPOINT='http://127.0.0.1:19400',
               EKB_MINIO_ACCESS_KEY=env['EKB_MINIO_ROOT_USER'],EKB_MINIO_SECRET_KEY=env['EKB_MINIO_ROOT_PASSWORD'],
               EKB_OPENSEARCH_URL='http://127.0.0.1:19210',EKB_NEO4J_URL='http://127.0.0.1:17479',EKB_MINIO_BUCKET='evokb-model-smoke',
               EKB_RAW_INDEX='evokb_model_smoke_raw',EKB_DERIVED_INDEX='evokb_model_smoke_derived',
               JAVA_HOME=subprocess.check_output(['/usr/libexec/java_home','-v','17']).decode().strip())
    cmd=['mvn','-B','-f','java/pom.xml','test','-pl','ekb-bootstrap','-am','-Dtest=RealModelSmokeTest','-Dsurefire.failIfNoSpecifiedTests=false']
    with args.output.with_suffix('.log').open('xb') as log:
        result=subprocess.run(cmd,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    xml=ROOT/'java/ekb-bootstrap/target/surefire-reports/TEST-cn.lgs.knowledgebase.RealModelSmokeTest.xml'
    counts={key:int(ET.parse(xml).getroot().get(key,0)) for key in ['tests','failures','errors','skipped']}
    passed=result.returncode==0 and counts==dict(tests=1,failures=0,errors=0,skipped=0)
    report={'status':'PASS_REAL_LUNA_TERRA_SMOKE' if passed else 'FAIL_REAL_LUNA_TERRA_SMOKE',
            'actualOperationTime':datetime.now(timezone.utc).isoformat(),'counts':counts,'schema':args.schema,'migrations':migrations,
            'command':cmd,'smallModel':env['EKB_SMALL_MODEL'],'largeModel':env['EKB_LARGE_MODEL'],
            'logSha256':hashlib.sha256(args.output.with_suffix('.log').read_bytes()).hexdigest(),
            'boundary':'Two real model connectivity calls, isolated empty context. Does not assert business data recovery or QA accuracy.'}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    args.output.with_suffix('.sql').write_text('-- New isolated model smoke context; no DROP/TRUNCATE.\n'+create+'\n-- Apply the recorded deploy/mysql/init migrations to this schema.\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ['migrations','command']},ensure_ascii=False))
    if not passed:raise SystemExit(1)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--schema',required=True);p.add_argument('--output',type=Path,required=True)
    run(p.parse_args())
