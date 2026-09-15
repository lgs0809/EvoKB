#!/usr/bin/env python3
"""Verify the actual CUA query, role boundaries and downloaded ZIP bytes through normal target APIs."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import io
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:18481/api/enterprise'


def client(account=None):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path, data=None):
        req = urllib.request.Request(BASE + path, data=None if data is None else json.dumps(data).encode(),
                                     headers={'Content-Type': 'application/json'})
        try:
            with opener.open(req, timeout=30) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as failure:
            return failure.code, json.load(failure)
    if account:
        status, body = request('/auth/login', account)
        if status != 200 or body['user']['user_id'] != account['user_id']:
            raise ValueError('Normal identity login failed')
    return request, opener


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--session', required=True)
    p.add_argument('--batch', required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if args.output.exists():
        raise ValueError('Use a fresh report path')
    accounts = json.loads((ROOT / '.tmp/target/accounts.json').read_text())
    admin, opener = client(accounts['admin'])
    reader, _ = client(accounts['reader'])
    anon, _ = client()
    checks = {}
    observations = []
    def check(name, passed, status=None):
        checks[name] = bool(passed)
        if status is not None:
            observations.append({'check': name, 'status': status})
    status, _ = anon('/documents'); check('anonymousCannotReadDocuments', status == 401, status)
    for path in ['/users', '/model-configurations', '/quality']:
        status, _ = reader(path); check('readerDenied' + path, status == 403, status)
    for path in ['/overview', '/quality', '/facets', '/documents', '/wikis', '/tasks', '/audit', '/graph', '/query/history', '/query/groups']:
        status, _ = admin(path); check('adminReadApi' + path, status == 200, status)
    status, _ = reader('/query/session?id=' + urllib.parse.quote(args.session))
    check('readerCannotReadAdminSession', status == 400, status)
    status, _ = reader('/batch?id=' + urllib.parse.quote(args.batch))
    check('readerCannotReadAdminBatch', status == 403, status)
    status, answer = admin('/query/session?id=' + urllib.parse.quote(args.session))
    check('realQueryAnswered', status == 200 and answer.get('status') == 'ANSWERED' and not answer.get('evidence_only'))
    text = answer.get('answer', '')
    check('crossDocumentAnswerContainsRequiredFacts', all(s in text for s in ['15', '发票', '900', '24', '30']))
    citations = answer.get('citations', [])
    evidence_ids = [c['evidence']['evidence_id'] for c in citations]
    check('fiveActualCitations', len(citations) == len(set(evidence_ids)) == 5)
    status, published = admin('/package', {'evidence_ids': evidence_ids})
    check('normalApiPackagesSources', status == 200)
    if status == 200:
        # Presigned URL remains private; never write it to a report or console.
        with opener.open(urllib.parse.urljoin('http://127.0.0.1:18481', published['download_url']), timeout=30) as response:
            content = response.read()
            check('actualCompleteZipTransfer', response.status == 200 and len(content) == int(response.headers['Content-Length']))
        args.output.with_suffix('.zip').write_bytes(content)
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            versions = manifest['versions']
            check('zipContainsOnlyTwoCitedDocuments', len(versions) == 2 and len(set(v['document_id'] for v in versions)) == 2)
            check('manifestKeepsExactCitationIds', set(manifest['evidence_ids']) == set(evidence_ids))
            hashes = {}
            for version in versions:
                name = version['original_filename']
                actual = archive.read(version['archive_path'])
                expected = (ROOT / 'deploy/acceptance/target-fixtures' / name).read_bytes()
                check('downloadedOriginalMatchesFixture_' + name, actual == expected)
                hashes[name] = hashlib.sha256(actual).hexdigest()
            observations.append({'packageId': published['package_id'], 'zipSha256': hashlib.sha256(content).hexdigest(),
                                 'originalFileHashes': hashes, 'manifest': manifest})
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    result = {'status': 'PASS_TARGET_NORMAL_API_AND_ACTUAL_ZIP' if all(checks.values()) else 'FAIL_TARGET_NORMAL_API_AND_ACTUAL_ZIP',
              'checks': checks, 'observations': observations, 'sessionId': args.session, 'batchId': args.batch,
              'jarSha256': state['jarSha256'], 'actualOperationTime': datetime.now(timezone.utc).isoformat(),
              'boundary': 'Actual CUA question cross-checked through normal APIs. ZIP transfer verified by an API client; CUA download-event capture did not succeed. Does not certify confidential-document clearance or successful-download usage accounting.'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': result['status'], 'passed': sum(checks.values()), 'failed': [k for k, v in checks.items() if not v]}, ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
