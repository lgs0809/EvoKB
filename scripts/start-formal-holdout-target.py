#!/usr/bin/env python3
"""Start the verified EvoKB JAR in a dedicated local holdout data space."""
import argparse
from datetime import datetime, timezone
import fcntl
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from formal_target import FormalTarget, ISOLATED_TARGETS, POSTGRES, ROOT


def pg(sql):
    return subprocess.run(['docker', 'exec', '-i', POSTGRES, 'psql', '-XAt',
                           '-U', 'evokb_target', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1'],
                          input=sql, text=True, capture_output=True, check=True, timeout=30).stdout.strip()


def seed_admin(target):
    accounts = target.private / 'permission-accounts.json'
    value = json.loads(accounts.read_text()) if accounts.exists() else {}

    def save():
        with tempfile.NamedTemporaryFile(mode='w', dir=target.private, delete=False) as stream:
            json.dump(value, stream)
        Path(stream.name).replace(accounts)

    if 'bootstrap' not in value:
        value['bootstrap'] = value.get('admin') or dict(username='formal_holdout_admin',
            display_name='留出评测初始化', password=secrets.token_urlsafe(24))
        value['admin'] = dict(username='formal_holdout_evaluator', display_name='复杂留出评测',
                              password=secrets.token_urlsafe(24), role='ADMIN', security_clearance=30)
        save()
    bootstrap = value['bootstrap']
    admin = value['admin']
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(path, payload=None):
        req = urllib.request.Request(target.base + '/api/enterprise' + path,
                                     data=json.dumps(payload).encode() if payload else None,
                                     headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=15) as response:
            return json.load(response)

    if call('/auth/status')['setup_required']:
        call('/auth/setup', bootstrap)
    call('/auth/login', bootstrap)
    if not any(user['username'] == admin['username'] for user in call('/users')['users']):
        call('/users', admin)
    user = call('/auth/login', admin)['user']
    if user['username'] != admin['username'] or user['role'] != 'ADMIN' or user['security_clearance'] != 30:
        raise ValueError('The isolated admin does not have the expected fixture identity')
    if 'user_id' in admin and admin['user_id'] != user['user_id']:
        raise ValueError('The existing fixture identity changed')
    admin['user_id'] = user['user_id']
    save()


def start(args, target):
    spec = importlib.util.spec_from_file_location('deploy_target', ROOT / 'scripts/deploy-target.py')
    deploy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deploy)
    jar, env, _ = deploy.prepare_start(args)
    jar_hash = deploy.digest(jar)
    artifact = deploy.PRIVATE / 'artifacts' / jar_hash / 'evokb.jar'
    if not artifact.is_file() or deploy.digest(artifact) != jar_hash:
        raise ValueError('Deploy this exact verified artifact to the managed target first')
    state = target.private / 'process.json'
    if state.exists():
        previous = json.loads(state.read_text())
        if deploy.identity(previous['pid']) == previous['processBirth']:
            previous = target.process()
            if jar_hash != previous['jarSha256']:
                raise ValueError('The running formal artifact differs from the verified build')
            seed_admin(target)
            print(json.dumps(previous))
            return
    port = target.port
    redis_database = str(target.redis_database)
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1', port)) == 0:
            raise ValueError('Another process owns the formal target port')
    owned = target.private / 'database-created'
    exists = pg(f"SELECT 1 FROM pg_database WHERE datname='{target.database}'") == '1'
    if exists != owned.is_file():
        raise ValueError('Formal database ownership mismatch; preserve the existing database')
    if not exists:
        redis_size = subprocess.check_output(['docker', 'exec', 'evokb-target-redis-1', 'redis-cli',
                                               '-n', redis_database, 'DBSIZE'], text=True, timeout=10).strip()
        if redis_size != '0':
            raise ValueError('The isolated Redis database is in use')
        pg(f'CREATE DATABASE {target.database} OWNER evokb_target')
        owned.touch(mode=0o600, exist_ok=False)
    env.update(EKB_SERVER_PORT=str(port), EKB_JDBC_URL=f'jdbc:postgresql://127.0.0.1:15465/{target.database}?stringtype=unspecified',
               EKB_REDIS_DATABASE=redis_database, EKB_RAW_STREAM=target.stream,
               EKB_MILVUS_COLLECTION=target.database, EKB_MINIO_BUCKET=target.bucket,
               EKB_SESSION_COOKIE_NAME=target.session_cookie, EKB_STAGING_ROOT=str(target.private / 'staging'))
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    log = target.private / f'backend-{stamp}.log'
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()) / 'bin/java'
    with os.fdopen(os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
        process = subprocess.Popen([str(java), '-Xms128m', '-Xmx384m',
                                    '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                    '-jar', str(artifact)], cwd=ROOT, env=env, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    receipt = dict(pid=process.pid, processBirth=deploy.identity(process.pid), artifact=str(artifact),
                   jarSha256=jar_hash, url=target.base, log=str(log), database=target.database,
                   redisDatabase=target.redis_database, collection=target.database, bucket=target.bucket)
    state.write_text(json.dumps(receipt, indent=2) + '\n')
    state.chmod(0o600)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline and process.poll() is None:
        try:
            with opener.open(target.base + '/actuator/health', timeout=3) as response:
                if json.load(response).get('status') == 'UP':
                    break
        except (OSError, ValueError):
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Formal target startup failed; inspect the retained private log')
    seed_admin(target)
    print(json.dumps(receipt))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verification', required=True, type=Path)
    parser.add_argument('--target', choices=ISOLATED_TARGETS, default='holdout')
    args = parser.parse_args()
    target = FormalTarget(name=args.target)
    target.private.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (target.private / 'start.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        start(args, target)


if __name__ == '__main__':
    main()
