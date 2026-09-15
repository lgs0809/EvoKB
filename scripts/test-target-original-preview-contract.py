#!/usr/bin/env python3
"""Read real authenticated source metadata and original bytes; never invoke a model."""

import argparse
import datetime
import hashlib
import http.cookiejar
import json
import re
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--evidence-id', action='append', required=True)
parser.add_argument('--jar-sha256', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if args.output.exists():
    raise SystemExit('Fresh output required; preserve previous failed observations')
if not all(re.fullmatch('[0-9a-f-]{36}', value) for value in args.evidence_id):
    raise SystemExit('Evidence IDs must be canonical UUIDs')
state = json.loads((REPO / '.tmp/target/process.json').read_text())
if state['jarSha256'] != args.jar_sha256:
    raise SystemExit('Report must bind to the actually deployed immutable artifact')
account = json.loads((REPO / '.tmp/target/accounts.json').read_text())['reader']
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
report = {
    'status': 'RUNNING_ACTUAL_ORIGINAL_PREVIEW_CONTRACT',
    'deployedArtifact': state,
    'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'boundary': 'Normal Reader HTTP, streaming exact original bytes and read-only PG. No writes, source exports, model calls or browser interaction. Actual browser inline preview is a separate required proof.',
    'observations': [],
    'checks': {},
}


def request(path, body=None):
    req = urllib.request.Request(
        'http://127.0.0.1:18481/api/enterprise' + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'},
    )
    with opener.open(req, timeout=15) as response:
        return response.status, json.load(response)


try:
    status, login = request('/auth/login', account)
    assert status == 200 and login['user']['user_id'] == account['user_id']
    ids = ','.join("'" + value + "'" for value in args.evidence_id)
    sql = (
        "SELECT json_build_object('evidenceId',e.evidence_id,'versionId',e.version_id,"
        "'filename',v.original_filename,'sha256',v.sha256,'active',e.active,"
        "'currentSource',a.version_id IS NOT NULL)::text "
        "FROM evidence e JOIN document_version v ON v.version_id=e.version_id "
        "LEFT JOIN kb_source_authority a ON a.version_id=e.version_id "
        "WHERE e.evidence_id IN (" + ids + ");"
    )
    args.output.with_suffix('.readback.sql').parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix('.readback.sql').write_text(sql)
    db = subprocess.run(
        ['docker', 'exec', 'evokb-target-postgres-1', 'psql', '-U', 'evokb_target',
         '-d', 'evokb_target', '-At', '-c', sql],
        capture_output=True, text=True, timeout=15,
    )
    db.check_returncode()
    rows = [json.loads(line) for line in db.stdout.splitlines() if line]
    native = {row['evidenceId']: row for row in rows}
    assert set(native) == set(args.evidence_id), 'All requested evidence must exist'
    for evidence_id in args.evidence_id:
        status, source = request('/evidence/source?id=' + evidence_id)
        row = native[evidence_id]
        key = evidence_id + ':'
        checks = report['checks']
        checks[key + 'readerSourceMetadataAvailable'] = status == 200
        checks[key + 'sourceFilenameMatchesAuthoritativeOriginal'] = source.get('source_filename') == row['filename']
        checks[key + 'versionIsCurrentAndEvidenceActive'] = source['version_id'] == row['versionId'] and row['active'] and row['currentSource']
        assert source['size_bytes'] <= 2 * 1024 * 1024, 'This small-fixture proof caps actual streamed bytes at 2MiB'
        url = urllib.parse.urljoin('http://127.0.0.1:18481', source['view_url'])
        parsed = urllib.parse.urlsplit(url)
        assert parsed.hostname in ['127.0.0.1', 'localhost'] and parsed.scheme == 'http', 'Local original endpoint required'
        digest = hashlib.sha256()
        size = 0
        with opener.open(url, timeout=15) as response:
            while chunk := response.read(65536):
                size += len(chunk)
                assert size <= 2 * 1024 * 1024, 'Response exceeds bounded fixture proof'
                digest.update(chunk)
        checks[key + 'actualOriginalViewStreamsAllDeclaredBytes'] = size == source['size_bytes']
        checks[key + 'actualOriginalBytesMatchNativeSHA256'] = digest.hexdigest() == row['sha256']
        report['observations'].append({
            'evidenceId': evidence_id,
            'versionId': source['version_id'],
            'sourceFilename': source.get('source_filename'),
            'authoritativeFilename': row['filename'],
            'objectBasename': source['object_key'].rsplit('/', 1)[-1],
            'actualObjectContentType': source.get('content_type'),
            'bytes': size,
            'sha256': digest.hexdigest(),
        })
    report['status'] = 'PASS_ACTUAL_ORIGINAL_PREVIEW_CONTRACT' if all(report['checks'].values()) else 'FAIL_ACTUAL_ORIGINAL_PREVIEW_CONTRACT'
except Exception as error:
    report.update(status='FAIL_ACTUAL_ORIGINAL_PREVIEW_CONTRACT', failure=type(error).__name__ + ': ' + str(error))
report.update(finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({'status': report['status'], 'passed': sum(report['checks'].values()), 'failed': [key for key, value in report['checks'].items() if not value], 'output': str(args.output)}, ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
