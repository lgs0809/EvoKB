#!/usr/bin/env python3
"""Loopback-only acceptance deployment; preserve all existing resources and data.

Reuse the repository Compose dependencies in their own named project, and run
the already-tested Java/web artifacts locally. No production, reset or volume
deletion command is provided. Credentials stay in ignored private files.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT/'.tmp/acceptance'
ENV = ROOT/'.env.acceptance'
STATE = PRIVATE/'process.json'
URL = 'http://127.0.0.1:18480'


def read_env():
    return dict(line.split('=', 1) for line in ENV.read_text().splitlines() if line and not line.startswith('#'))


def compose(*args):
    return subprocess.run(['docker','compose','--project-name','evokb-acceptance','--env-file',str(ENV),
                           '-f',str(ROOT/'deploy/docker-compose.yml'),'-f',str(ROOT/'deploy/acceptance/compose.resources.yml'),
                           *args],cwd=ROOT,check=True)


def init(args):
    if ENV.exists():
        print(json.dumps({'status':'EXISTING_CONFIG_PRESERVED','url':URL}));return
    PRIVATE.mkdir(parents=True,exist_ok=True);PRIVATE.chmod(0o700)
    model = json.loads(args.model_config.read_text()) if args.model_config else {}
    if model and (model.get('smallModel')!='gpt-5.6-luna' or model.get('largeModel')!='gpt-5.6-terra'):
        raise ValueError('Keep the explicitly authorized Luna/Terra roles')
    config = {'EKB_BIND_ADDRESS':'127.0.0.1','EKB_SERVER_HOST':'127.0.0.1','EKB_SERVER_PORT':'18480',
              'EKB_MYSQL_PORT':'13365','EKB_MYSQL_DATABASE':'evokb_acceptance','EKB_MYSQL_USER':'evokb_acceptance',
              'EKB_MYSQL_PASSWORD':secrets.token_hex(24),'EKB_MYSQL_ROOT_PASSWORD':secrets.token_hex(24),
              'EKB_MYSQL_POOL_SIZE':'6','EKB_MINIO_PORT':'19400','EKB_MINIO_CONSOLE_PORT':'19401',
              'EKB_MINIO_ROOT_USER':'evokb_acceptance','EKB_MINIO_ROOT_PASSWORD':secrets.token_hex(24),
              'EKB_MINIO_BUCKET':'evokb-acceptance','EKB_MINIO_PUBLIC_ENDPOINT':'http://127.0.0.1:19400',
              'EKB_OPENSEARCH_PORT':'19210','EKB_OPENSEARCH_JAVA_OPTS':'-Xms384m -Xmx384m',
              'EKB_NEO4J_HTTP_PORT':'17479','EKB_NEO4J_BOLT_PORT':'17692',
              'EKB_NEO4J_AUTH':'neo4j/'+secrets.token_hex(24),'EKB_NEO4J_HEAP_INITIAL':'128m',
              'EKB_NEO4J_HEAP_MAX':'256m','EKB_NEO4J_PAGECACHE':'128m',
              'EKB_CHAT_BASE_URL':model.get('chatBaseUrl',''),'EKB_CHAT_API_KEY':model.get('chatApiKey',''),
              'EKB_SMALL_MODEL':model.get('smallModel','gpt-5.6-luna'),'EKB_LARGE_MODEL':model.get('largeModel','gpt-5.6-terra'),
              'EKB_CHAT_TIMEOUT_SECONDS':'60','EKB_ASSISTANT_FALLBACK':'disabled',
              'EKB_EMBEDDING_BASE_URL':model.get('embeddingBaseUrl',''),'EKB_EMBEDDING_MODEL':model.get('embeddingModel',''),
              'EKB_EMBED_DIMS':'1024','EKB_EMBED_BATCH_SIZE':'1','EKB_EMBED_MAX_CONCURRENCY':'1',
              'EKB_RERANK_BASE_URL':model.get('rerankBaseUrl',''),'EKB_RERANK_MODEL':model.get('rerankModel',''),
              'EKB_MINERU_URL':model.get('minerUUrl',''),'EKB_RAW_INDEX':'evokb_acceptance_raw_v1',
              'EKB_DERIVED_INDEX':'evokb_acceptance_derived_v1'}
    if any('\n' in v or '\r' in v for v in config.values()):raise ValueError('Invalid configuration line')
    ENV.write_text('\n'.join(k+'='+v for k,v in config.items())+'\n');ENV.chmod(0o600)
    print(json.dumps({'status':'PASS_PRIVATE_ISOLATED_CONFIG','url':URL,'smallModel':config['EKB_SMALL_MODEL'],
                      'largeModel':config['EKB_LARGE_MODEL'],'minerUConfigured':bool(config['EKB_MINERU_URL']),
                      'rerankerConfigured':bool(config['EKB_RERANK_BASE_URL'])}))


def healthy():
    try:
        with urllib.request.urlopen(URL+'/actuator/health',timeout=3) as response:
            return json.load(response).get('status')=='UP'
    except Exception:return False


def build_web(args):
    """Use the same atomic publisher as the PostgreSQL/Milvus target."""
    from web_release import build
    build(ROOT, PRIVATE, args.log)


def start(args):
    if STATE.exists():
        previous=json.loads(STATE.read_text())
        try:os.kill(previous['pid'],0)
        except ProcessLookupError:pass
        else:raise ValueError('An existing managed process is present; inspect status before restarting')
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1',18480))==0:raise ValueError('Port 18480 belongs to another process')
    if 'BUILD SUCCESS' not in args.backend_test_log.read_text():raise ValueError('Actual successful backend verification required')
    if 'built in' not in args.web_build_log.read_text():raise ValueError('Actual successful web build required')
    jar=ROOT/'java/ekb-bootstrap/target/evokb.jar'
    verification=json.loads(args.verification_report.read_text())
    jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
    if (verification.get('status')!='PASS_FULL_JAVA_VERIFY' or verification.get('jarSha256')!=jar_hash
        or verification.get('logSha256')!=hashlib.sha256(args.backend_test_log.read_bytes()).hexdigest()
        or verification.get('counts',{}).get('failures')!=0 or verification.get('counts',{}).get('errors')!=0):
        raise ValueError('Verification report does not match the current tested artifact and log')
    for path,digest in verification.get('backendSources',{}).items():
        if hashlib.sha256((ROOT/path).read_bytes()).hexdigest()!=digest:
            raise ValueError('Backend source changed since full verification: '+path)
    artifact=PRIVATE/'artifacts'/jar_hash/'evokb.jar';artifact.parent.mkdir(parents=True,exist_ok=True)
    if not artifact.exists():shutil.copy2(jar,artifact)
    if hashlib.sha256(artifact.read_bytes()).hexdigest()!=jar_hash:raise ValueError('Preserved artifact hash differs')
    env=dict(os.environ,**read_env())
    env.update(EKB_JDBC_URL='jdbc:mysql://127.0.0.1:13365/evokb_acceptance?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC',
               EKB_MINIO_ENDPOINT='http://127.0.0.1:19400',EKB_MINIO_ACCESS_KEY=env['EKB_MINIO_ROOT_USER'],
               EKB_MINIO_SECRET_KEY=env['EKB_MINIO_ROOT_PASSWORD'],EKB_OPENSEARCH_URL='http://127.0.0.1:19210',
               EKB_NEO4J_URL='http://127.0.0.1:17479',EKB_WEB_DIR=str(ROOT/'web/dist'),
               EKB_STAGING_ROOT=str(PRIVATE/'staging'))
    java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17']).decode().strip())/'bin/java'
    log=PRIVATE/('backend-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'.log')
    with log.open('xb') as stream:
        process=subprocess.Popen([str(java),'-Xms128m','-Xmx384m','-jar',str(artifact)],cwd=ROOT,env=env,
                                 stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
    record={'pid':process.pid,'url':URL,'jarSha256':jar_hash,'artifact':str(artifact),
            'backendTestLogSha256':hashlib.sha256(args.backend_test_log.read_bytes()).hexdigest(),
            'webBuildLogSha256':hashlib.sha256(args.web_build_log.read_bytes()).hexdigest(),
            'log':str(log),'startedAt':datetime.now(timezone.utc).isoformat()}
    record['processBirth']=subprocess.check_output(['ps','-p',str(process.pid),'-o','lstart=']).decode().strip()
    STATE.write_text(json.dumps(record,indent=2)+'\n');STATE.chmod(0o600)
    deadline=time.monotonic()+90
    while time.monotonic()<deadline and process.poll() is None:
        if healthy():print(json.dumps({'status':'PASS_STARTED_HEALTH_ONLY','url':URL,'pid':process.pid,'jarSha256':record['jarSha256']}));return
        time.sleep(1)
    raise RuntimeError('Application start failed or timed out; inspect the preserved private log')


def stop():
    if not STATE.exists():print(json.dumps({'status':'NO_MANAGED_PROCESS'}));return
    state=json.loads(STATE.read_text())
    observed=subprocess.run(['ps','-p',str(state['pid']),'-o','lstart='],capture_output=True,text=True)
    if not observed.stdout.strip():print(json.dumps({'status':'ALREADY_STOPPED_DATA_PRESERVED'}));return
    if observed.stdout.strip()!=state.get('processBirth'):raise ValueError('PID reused; do not stop another process')
    os.kill(state['pid'],signal.SIGTERM)
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        current=subprocess.run(['ps','-p',str(state['pid']),'-o','lstart='],capture_output=True,text=True)
        if not current.stdout.strip():
            print(json.dumps({'status':'STOPPED_DATA_PRESERVED','pid':state['pid']}));return
        if current.stdout.strip()!=state.get('processBirth'):raise ValueError('PID reused while waiting; do not signal another process')
        time.sleep(0.25)
    print(json.dumps({'status':'STOP_REQUESTED_STILL_EXITING_DATA_PRESERVED','pid':state['pid']}))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    s=sub.add_parser('init');s.add_argument('--model-config',type=Path)
    sub.add_parser('dependencies');sub.add_parser('status');sub.add_parser('stop')
    s=sub.add_parser('build-web');s.add_argument('--log',type=Path,required=True)
    s=sub.add_parser('start');s.add_argument('--backend-test-log',type=Path,required=True);s.add_argument('--web-build-log',type=Path,required=True);s.add_argument('--verification-report',type=Path,required=True)
    args=p.parse_args()
    if args.command=='init':init(args)
    elif args.command=='dependencies':compose('config','--quiet');compose('up','-d','--wait','--wait-timeout','240','mysql','minio','opensearch','neo4j')
    elif args.command=='start':start(args)
    elif args.command=='build-web':build_web(args)
    elif args.command=='stop':stop()
    else:
        print(json.dumps({'status':'OBSERVED','url':URL,'healthy':healthy(),'managedProcessPresent':STATE.exists()}));compose('ps','mysql','minio','opensearch','neo4j')
