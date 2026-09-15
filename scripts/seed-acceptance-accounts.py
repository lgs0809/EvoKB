#!/usr/bin/env python3
"""Create isolated acceptance identities through normal APIs, never reset passwords.

Credentials stay in .tmp/acceptance/accounts.json (ignored, mode 0600).
Reruns preserve existing users and fail safely if the identity cannot be verified.
"""
import argparse
from datetime import datetime, timezone
import http.cookiejar
import json
from pathlib import Path
import secrets
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT/'.tmp/acceptance'
ACCOUNTS = PRIVATE/'accounts.json'
BASE = 'http://127.0.0.1:18480/api/enterprise'


def run(report):
    if report.exists():
        raise ValueError('Choose a new report path; preserve earlier results')
    PRIVATE.mkdir(parents=True, exist_ok=True)
    PRIVATE.chmod(0o700)
    if not ACCOUNTS.exists():
        accounts = {
            'admin': {'username':'acceptance_admin', 'display_name':'验收管理员', 'role':'ADMIN', 'password':secrets.token_urlsafe(24)},
            'reader': {'username':'acceptance_reader', 'display_name':'验收只读成员', 'role':'USER', 'password':secrets.token_urlsafe(24)},
        }
        ACCOUNTS.write_text(json.dumps(accounts, ensure_ascii=False, indent=2)+'\n')
        ACCOUNTS.chmod(0o600)
    accounts = json.loads(ACCOUNTS.read_text())
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(path, data=None):
        req = urllib.request.Request(BASE+path, data=None if data is None else json.dumps(data).encode(),
                                     headers={'Content-Type':'application/json'})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    initial = call('/auth/status')
    admin = accounts['admin']
    if initial['setup_required']:
        call('/auth/setup', admin)
    else:
        call('/auth/login', admin)
    observed = call('/auth/me')['user']
    if observed['username'] != admin['username'] or observed['role'] != 'ADMIN':
        raise ValueError('Existing identity differs; do not replace it')
    users = call('/users')['users']
    for role, account in accounts.items():
        matching = [user for user in users if user['username']==account['username']]
        if not matching:
            matching = [call('/users', account)['user']]
        if len(matching)!=1 or matching[0]['role']!=account['role'] or matching[0]['status']!='ACTIVE':
            raise ValueError('Existing user differs; do not modify its role or status')
        account['user_id'] = matching[0]['user_id']
    ACCOUNTS.write_text(json.dumps(accounts, ensure_ascii=False, indent=2)+'\n')
    ACCOUNTS.chmod(0o600)
    for account in accounts.values():
        user = call('/auth/login', account)['user']
        if user['user_id'] != account['user_id']:
            raise ValueError('Login identity mismatch')
    final = call('/auth/status')
    result = {'status':'PASS_NORMAL_API_IDENTITY_SEED', 'initialUsers':initial['users'], 'finalUsers':final['users'],
              'actualOperationTime':datetime.now(timezone.utc).isoformat(),
              'users':[{k:v for k,v in account.items() if k!='password'} for account in accounts.values()],
              'boundary':'Only the isolated local acceptance environment; no password reset or direct database writes.'}
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__=='__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--report', type=Path, required=True)
    p.add_argument('--environment', choices=['legacy', 'target'], default='legacy')
    args = p.parse_args()
    if args.environment == 'target':
        PRIVATE = ROOT/'.tmp/target'
        ACCOUNTS = PRIVATE/'accounts.json'
        BASE = 'http://127.0.0.1:18481/api/enterprise'
    run(args.report)
