#!/usr/bin/env python3
"""Test real target/legacy cookie isolation in one HTTP client, without exposing credentials or tokens."""
import argparse
from datetime import datetime, timezone
import http.cookiejar
import json
from pathlib import Path
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if args.output.exists():
        raise ValueError('Use a fresh result path')
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    checks = {}
    def request(port, path, data=None):
        req = urllib.request.Request(f'http://127.0.0.1:{port}/api/enterprise' + path,
                                     data=None if data is None else json.dumps(data).encode(),
                                     headers={'Content-Type': 'application/json'})
        try:
            with opener.open(req, timeout=30) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as failure:
            return failure.code, json.load(failure)
    accounts = {}
    for kind, port, directory in [('legacy', 18480, 'acceptance'), ('target', 18481, 'target')]:
        account = json.loads((ROOT / f'.tmp/{directory}/accounts.json').read_text())['admin']
        accounts[kind] = account
        status, body = request(port, '/auth/login', {k: account[k] for k in ['username', 'password']})
        checks[kind + 'NormalLogin'] = status == 200 and body.get('user', {}).get('user_id') == account['user_id']
    checks['independentCookiesOnSameHost'] = {'ekb_session', 'ekb_target_session'} <= {c.name for c in cookies}
    for kind, port in [('legacy', 18480), ('target', 18481)]:
        status, body = request(port, '/auth/me')
        checks[kind + 'IdentitySurvivesOtherLogin'] = status == 200 and body.get('user', {}).get('user_id') == accounts[kind]['user_id']
    status, _ = request(18481, '/auth/logout', {})
    checks['targetLogoutSucceeds'] = status == 200
    status, _ = request(18481, '/auth/me')
    checks['targetLogoutRevokesTargetSession'] = status == 401
    status, body = request(18480, '/auth/me')
    checks['targetLogoutPreservesLegacyIdentity'] = status == 200 and body.get('user', {}).get('user_id') == accounts['legacy']['user_id']
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    result = {'status': 'PASS_REAL_SESSION_ISOLATION' if all(checks.values()) else 'FAIL_REAL_SESSION_ISOLATION',
              'checks': checks, 'jarSha256': state['jarSha256'], 'actualOperationTime': datetime.now(timezone.utc).isoformat(),
              'boundary': 'One cookie jar and the actual two deployed applications; routine login/logout only.'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
