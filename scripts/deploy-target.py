#!/usr/bin/env python3
"""Deploy the PostgreSQL/Milvus target locally, preserving the legacy environment and every attempt."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import secrets
import signal
import socket
import subprocess
import time
import urllib.request
from web_release import build as build_web

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / '.tmp/target'
STATE = PRIVATE / 'process.json'
URL = 'http://127.0.0.1:18481'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def settings():
    return dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                if line and not line.startswith('#'))


def init(args):
    config_path = ROOT / '.env.target'
    if config_path.exists():
        print(json.dumps(dict(status='EXISTING_PRIVATE_CONFIG_PRESERVED', url=URL)))
        return
    previous = subprocess.run(['docker', 'inspect', 'evokb-target-postgres-1'], capture_output=True)
    if previous.returncode == 0:
        raise ValueError('A target database already exists. Restore its private config; do not generate replacement credentials')
    source = dict(line.split('=', 1) for line in args.from_env.read_text().splitlines()
                  if line and not line.startswith('#'))
    required = ['EKB_MINIO_ROOT_USER', 'EKB_MINIO_ROOT_PASSWORD', 'EKB_CHAT_BASE_URL',
                'EKB_CHAT_API_KEY', 'EKB_SMALL_MODEL', 'EKB_LARGE_MODEL']
    if any(not source.get(key) for key in required):
        raise ValueError('An existing authorized private model/MinIO configuration is required')
    if source['EKB_SMALL_MODEL'] != 'gpt-5.6-luna' or source['EKB_LARGE_MODEL'] != 'gpt-5.6-terra':
        raise ValueError('Keep the authorized Luna/Terra model roles')
    # Copy only relevant contracts; do not carry obsolete MySQL/OpenSearch/Neo4j credentials forward.
    prefixes = ('EKB_CHAT_', 'EKB_EMBED', 'EKB_RERANK_', 'EKB_MINERU_')
    config = {key: value for key, value in source.items() if key.startswith(prefixes) or key in required}
    password = secrets.token_hex(24)
    config.update(EKB_BIND_ADDRESS='127.0.0.1', EKB_SERVER_HOST='127.0.0.1', EKB_SERVER_PORT='18481',
                  EKB_POSTGRES_PASSWORD=password, EKB_DB_USER='evokb_target', EKB_DB_PASSWORD=password,
                  EKB_DB_POOL_SIZE='6', EKB_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?stringtype=unspecified',
                  EKB_MILVUS_URL='http://127.0.0.1:19535', EKB_MILVUS_COLLECTION='knowledge_chunks',
                  EKB_REDIS_URL='redis://127.0.0.1:16385', EKB_MINIO_BUCKET='evokb-target',
                  EKB_MINIO_PUBLIC_ENDPOINT='http://127.0.0.1:19400', EKB_FLYWAY_BASELINE_ON_MIGRATE='false',
                  EKB_EMBEDDING_BASE_URL='http://127.0.0.1:8110', EKB_EMBED_DIMS='1024',
                  EKB_EMBEDDING_MODEL='Qwen/Qwen3-VL-Embedding-2B', EKB_RERANK_BASE_URL='http://127.0.0.1:8110',
                  EKB_RERANK_MODEL='Qwen/Qwen3-VL-Reranker-2B', EKB_ASSISTANT_FALLBACK='disabled',
                  EKB_SESSION_COOKIE_NAME='ekb_target_session', EKB_STAGING_ROOT=str(PRIVATE / 'staging'))
    if any('\n' in value or '\r' in value for value in config.values()):
        raise ValueError('Invalid configuration line')
    PRIVATE.mkdir(parents=True, exist_ok=True)
    PRIVATE.chmod(0o700)
    descriptor = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write('\n'.join(key + '=' + value for key, value in config.items()) + '\n')
    print(json.dumps(dict(status='PASS_PRIVATE_TARGET_CONFIG_CREATED', url=URL)))


def identity(pid):
    return subprocess.run(['ps', '-p', str(pid), '-o', 'lstart='], capture_output=True, text=True).stdout.strip()


def health():
    try:
        with urllib.request.urlopen(URL + '/actuator/health', timeout=3) as response:
            return json.load(response).get('status') == 'UP'
    except Exception:
        return False


def check_dependencies():
    names = ['evokb-target-postgres-1', 'evokb-target-redis-1', 'evokb-target-etcd-1',
             'evokb-target-milvus-1', 'evokb-acceptance-minio-1']
    observed = []
    for name in names:
        result = subprocess.run(['docker', 'inspect', '--format', '{{json .State}}', name],
                                capture_output=True, text=True, timeout=10)
        if result.returncode:
            raise ValueError('Required existing local dependency is missing: ' + name)
        state = json.loads(result.stdout)
        healthy = state.get('Running') and state.get('Health', {}).get('Status') == 'healthy'
        observed.append(dict(name=name, running=bool(state.get('Running')),
                             health=state.get('Health', {}).get('Status', 'unconfigured')))
        if not healthy:
            raise ValueError('Required local dependency is not healthy: ' + name)
    for url in ['http://127.0.0.1:19095/healthz', 'http://127.0.0.1:19400/minio/health/ready']:
        with urllib.request.urlopen(url, timeout=5) as response:
            if response.status != 200:
                raise ValueError('Required local dependency failed its live health check')
    return observed


def prepare_start(args):
    report = json.loads(args.verification.read_text())
    jar = ROOT / 'java/ekb-bootstrap/target/evokb.jar'
    if report.get('status') != 'PASS_FULL_JAVA_VERIFY' or report.get('jarSha256') != digest(jar):
        raise ValueError('A successful full verification matching this artifact is required')
    if report.get('sourcesStableDuringVerification') is not True:
        raise ValueError('A verified before/after source manifest is required; use scripts/verify-backend.py')
    actual_sources = {str(p.relative_to(ROOT)) for p in (ROOT / 'java').rglob('*') if p.is_file()
                      and 'target' not in p.parts and (p.name == 'pom.xml' or '/src/' in str(p))}
    if actual_sources != set(report['backendSources']):
        raise ValueError('Backend source inventory changed since verification')
    for path, expected in report['backendSources'].items():
        if digest(ROOT / path) != expected:
            raise ValueError('Source changed since verification: ' + path)
    env = dict(os.environ, **settings())
    if env['EKB_JDBC_URL'].split('?', 1)[0] != 'jdbc:postgresql://127.0.0.1:15465/evokb_target':
        raise ValueError('Only the isolated PostgreSQL target is allowed')
    if env['EKB_SMALL_MODEL'] != 'gpt-5.6-luna' or env['EKB_LARGE_MODEL'] != 'gpt-5.6-terra':
        raise ValueError('Preserve the authorized Luna/Terra model roles')
    if env.get('EKB_SERVER_HOST') != '127.0.0.1' or env.get('EKB_SERVER_PORT') != '18481':
        raise ValueError('The target must remain loopback-only on its isolated port')
    if env.get('EKB_MILVUS_URL') != 'http://127.0.0.1:19535' or env.get('EKB_MILVUS_COLLECTION') != 'knowledge_chunks':
        raise ValueError('Use only the isolated single-collection Milvus target')
    env.update(EKB_MINIO_ENDPOINT='http://127.0.0.1:19400',
               EKB_MINIO_ACCESS_KEY=env['EKB_MINIO_ROOT_USER'], EKB_MINIO_SECRET_KEY=env['EKB_MINIO_ROOT_PASSWORD'],
               EKB_WEB_DIR=str(ROOT / 'web/dist'))
    if not (ROOT / 'web/dist/index.html').is_file():
        raise ValueError('Build the actual web assets first')
    dependencies = check_dependencies()
    return jar, env, dependencies


def start(args):
    jar, env, dependencies = prepare_start(args)
    if STATE.exists():
        state = json.loads(STATE.read_text())
        if identity(state['pid']):
            raise ValueError('A managed target process exists; use status or stop before replacing it')
    with socket.socket() as connection:
        if connection.connect_ex(('127.0.0.1', 18481)) == 0:
            raise ValueError('Another process owns the target port')
    PRIVATE.mkdir(parents=True, exist_ok=True)
    PRIVATE.chmod(0o700)
    artifact = PRIVATE / 'artifacts' / digest(jar) / 'evokb.jar'
    artifact.parent.mkdir(parents=True, exist_ok=True)
    if not artifact.exists():
        shutil.copy2(jar, artifact)
    if digest(artifact) != digest(jar):
        raise ValueError('Immutable target artifact checksum mismatch')
    log = PRIVATE / ('backend-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '.log')
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin/java'
    with log.open('xb') as stream:
        process = subprocess.Popen([str(java), '-Xms128m', '-Xmx384m',
                                    '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                    '-jar', str(artifact)], cwd=ROOT, env=env, stdout=stream,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    state = dict(pid=process.pid, processBirth=identity(process.pid), artifact=str(artifact),
                 jarSha256=digest(artifact), verificationSha256=digest(args.verification),
                 url=URL, log=str(log), startedAt=datetime.now(timezone.utc).isoformat())
    if STATE.exists():
        archive = PRIVATE / 'deployment-attempts'
        archive.mkdir(exist_ok=True)
        archive.chmod(0o700)
        previous = archive / ('process-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '.json')
        shutil.copy2(STATE, previous)
        previous.chmod(0o600)
    STATE.write_text(json.dumps(state, indent=2) + '\n')
    STATE.chmod(0o600)
    deadline = time.monotonic() + 90
    while process.poll() is None and time.monotonic() < deadline:
        if health():
            print(json.dumps(dict(status='PASS_TARGET_HEALTH_ONLY', **state)))
            return
        time.sleep(1)
    raise RuntimeError('Target did not start; the private log and artifact are retained')


def stop():
    if not STATE.exists():
        print('No managed target process'); return
    state = json.loads(STATE.read_text())
    birth = identity(state['pid'])
    if not birth:
        print('Target already stopped; data preserved'); return
    if birth != state['processBirth']:
        raise ValueError('PID reused; refuse to signal another process')
    os.kill(state['pid'], signal.SIGTERM)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and identity(state['pid']) == birth:
        time.sleep(0.25)
    print(json.dumps(dict(status='STOP_REQUESTED_DATA_PRESERVED', running=identity(state['pid']) == birth)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    init_parser = actions.add_parser('init')
    init_parser.add_argument('--from-env', type=Path, default=ROOT / '.env.acceptance')
    actions.add_parser('deps')
    web_parser = actions.add_parser('build-web')
    web_parser.add_argument('--log', type=Path, required=True)
    start_parser = actions.add_parser('start')
    start_parser.add_argument('--verification', type=Path, required=True)
    check_parser = actions.add_parser('check')
    check_parser.add_argument('--verification', type=Path, required=True)
    actions.add_parser('stop')
    actions.add_parser('status')
    args = parser.parse_args()
    if args.action == 'init':
        init(args)
    elif args.action == 'build-web':
        build_web(ROOT, PRIVATE, args.log)
    elif args.action == 'deps':
        subprocess.run(['docker', 'compose', '--project-name', 'evokb-target', '--env-file', '.env.target',
                        '-f', 'deploy/docker-compose.target.yml', 'up', '-d'], cwd=ROOT, check=True)
    elif args.action == 'start':
        start(args)
    elif args.action == 'check':
        jar, _, dependencies = prepare_start(args)
        print(json.dumps(dict(status='PASS_DEPLOYMENT_PREFLIGHT_ONLY', jarSha256=digest(jar),
                              dependencies=dependencies)))
    elif args.action == 'stop':
        stop()
    else:
        print(json.dumps(dict(healthy=health(), url=URL, managed=STATE.exists())))


if __name__ == '__main__':
    main()
