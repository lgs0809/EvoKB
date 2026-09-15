#!/usr/bin/env python3
"""Import marked target fixtures through normal APIs; preserve every existing document and attempt."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
from pathlib import Path
import subprocess
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:18481/api/enterprise'
FIXTURES = [('HT-MV-017采购合同.md', '法务', '合同', '2026-09-01'),
            ('HT-MV-017补充协议.md', '法务', '合同', '2026-09-20'),
            ('仓储安全规程.md', '仓储', '规程', '2026-09-01')]


def snapshot():
    # Read-only preflight avoids creating a new version under a colliding business name.
    sql = """SELECT COALESCE(json_agg(s ORDER BY s.document_id,s.version_id),'[]'::json)
        FROM (SELECT d.document_id,d.logical_name,v.version_id,v.lifecycle,v.sha256,
            row_to_json(m) metadata FROM document d
            JOIN document_version v ON v.document_id=d.document_id
            JOIN document_metadata m ON m.version_id=v.version_id) s;"""
    observed = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql',
                               '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', 'evokb_target', '-d', 'evokb_target'],
                              input=sql, text=True, capture_output=True, check=True, timeout=30)
    return json.loads(observed.stdout)


def multipart(file, values):
    boundary = 'evokb-fixture-' + uuid.uuid4().hex
    parts = []
    for name, value in values.items():
        parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'
                      + value + '\r\n').encode())
    parts += [(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file.name}"\r\n'
               'Content-Type: text/markdown; charset=utf-8\r\n\r\n').encode(),
              file.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()]
    return b''.join(parts), 'multipart/form-data; boundary=' + boundary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Choose a fresh report path; keep earlier results')
    before = snapshot()
    expected = {}
    for filename, _, _, _ in FIXTURES:
        path = ROOT / 'deploy/acceptance/target-fixtures' / filename
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        matching = [r for r in before if r['logical_name'] == path.stem]
        if matching and (len(matching) != 1 or matching[0]['sha256'] != digest
                         or matching[0]['lifecycle'] != 'ACTIVE'):
            raise ValueError('Existing data differs: ' + path.stem + '. No upload or replacement performed')
        expected[filename] = digest
    account = json.loads((ROOT / '.tmp/target/accounts.json').read_text())['admin']
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    login = urllib.request.Request(BASE + '/auth/login', data=json.dumps(account).encode(),
                                   headers={'Content-Type': 'application/json'})
    with opener.open(login, timeout=30) as response:
        if json.load(response)['user']['user_id'] != account['user_id']:
            raise ValueError('Normal admin login identity mismatch')
    results = []
    for filename, department, document_type, date in FIXTURES:
        file = ROOT / 'deploy/acceptance/target-fixtures' / filename
        content, content_type = multipart(file, dict(logical_name=file.stem, source_department=department,
                                                    document_type=document_type, company='星桥制造',
                                                    document_date=date, tags='本地验收,虚构数据'))
        request = urllib.request.Request(BASE + '/upload', data=content,
                                         headers={'Content-Type': content_type})
        with opener.open(request, timeout=180) as response:
            result = json.load(response)
            results.append(dict(filename=filename, sha256=expected[filename], httpStatus=response.status,
                                documentId=result['document_id'], versionId=result['version_id'],
                                reused=result['reused']))
    after = snapshot()
    checks = {'allOriginalRowsPreserved': all(row in after for row in before),
              'threeSeparateDocuments': len({r['documentId'] for r in results}) == 3,
              'onlyMissingFixturesAdded': len(after) == len(before) + sum(not r['reused'] for r in results),
              'actualHashesAndActiveVersions': all(any(row['version_id'] == r['versionId']
                   and row['document_id'] == r['documentId'] and row['sha256'] == r['sha256']
                   and row['lifecycle'] == 'ACTIVE' for row in after) for r in results)}
    report = dict(status='PASS_NORMAL_API_BUSINESS_SEED' if all(checks.values()) else 'FAIL_NORMAL_API_BUSINESS_SEED',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(), checks=checks, results=results,
                  rowsBefore=len(before), rowsAfter=len(after),
                  boundary='Normal upload APIs only. Read-only SQL verifies hashes and preservation; no task-state writes.')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
