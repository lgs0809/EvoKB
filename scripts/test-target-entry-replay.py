#!/usr/bin/env python3
"""Regenerate one actual owned browser answer package and test concurrent entry-credit replay."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--account', choices=['admin', 'public', 'confidential'], default='admin')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    uuid.UUID(args.session)
    if args.output.exists():
        raise ValueError('Use a fresh output and preserve every failed attempt')
    spec = importlib.util.spec_from_file_location('permission_client', ROOT/'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    account = json.loads((ROOT/'.tmp/target/permission-accounts.json').read_text())[args.account]
    request, opener = module.client(account)
    checks, observations, sql_queries = {}, {}, []
    def check(name, value):
        checks[name] = bool(value)
        if not value:
            raise AssertionError(name)
    def snapshot():
        sql = f"""SELECT json_build_object(
          'sources',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT * FROM source_usage_contribution WHERE session_id='{args.session}' ORDER BY user_id,document_id) t),
          'entries',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT * FROM asset_usage_contribution WHERE session_id='{args.session}' ORDER BY user_id,asset_id) t),
          'unsupported',(SELECT count(*) FROM asset_usage_contribution u WHERE u.session_id='{args.session}' AND NOT EXISTS(SELECT 1 FROM query_asset_expansion x JOIN package_answer_source a ON a.session_id=x.session_id AND a.version_id=x.source_version_id JOIN package_download_receipt r ON r.package_id=a.package_id WHERE x.session_id=u.session_id AND x.asset_id=u.asset_id AND r.receipt_id=u.receipt_id))
        );"""
        sql_queries.append(sql)
        return module.pg(sql)
    try:
        code, answer = request('/query/session?id='+args.session)
        check('actualOwnedAnswerHasPersistedCitations', code == 200 and answer['status'] == 'ANSWERED' and bool(answer['citations']))
        before = snapshot()
        check('actualBrowserDownloadHasPriorEntryContribution', bool(before['entries']) and before['unsupported'] == 0)
        evidence = [c['evidence']['evidence_id'] for c in answer['citations']]
        code, published = request('/package', dict(session_id=args.session, evidence_ids=evidence))
        check('normalApiRegeneratesOwnedAnswerPackage', code == 200 and {a['session_id'] for a in published['manifest']['answers']} == {args.session})
        observations['packageId'] = published['package_id']
        check('regenerationDoesNotChangeAnyPriorUsageFact', snapshot() == before)
        url = urllib.parse.urljoin(module.BASE, published['download_url'])
        receipt_sql = "SELECT count(*) FROM package_download_receipt WHERE package_id='"+published['package_id']+"';"
        sql_queries.append(receipt_sql)
        with opener.open(urllib.request.Request(url, method='HEAD'), timeout=30) as response:
            check('headHasNoBody', response.status == 200 and not response.read())
        try:
            with opener.open(urllib.request.Request(url, headers={'Range':'bytes=0-15'}), timeout=30):
                check('rangeRejected', False)
        except urllib.error.HTTPError as error:
            check('rangeRejected', error.code == 416)
        check('headAndRangeCannotCreateCompletionOrHeat', module.pg(receipt_sql) == 0 and snapshot() == before)
        def transfer(_):
            with opener.open(url, timeout=30) as response:
                content = response.read()
                if response.status != 200 or len(content) != int(response.headers['Content-Length']):
                    raise AssertionError('Incomplete real ZIP transfer')
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                manifest = json.loads(archive.read('manifest.json'))
                if {a['session_id'] for a in manifest['answers']} != {args.session}:
                    raise AssertionError('Answer identity differs')
                for source in manifest['versions']:
                    expected = ROOT/'deploy/acceptance/target-fixtures'/source['original_filename']
                    if archive.read(source['archive_path']) != expected.read_bytes():
                        raise AssertionError('Original bytes differ')
            return content
        with ThreadPoolExecutor(max_workers=6) as pool:
            files = list(pool.map(transfer, range(12)))
        args.output.with_suffix('.zip').write_bytes(files[0])
        hashes = [hashlib.sha256(f).hexdigest() for f in files]
        observations['transfers'] = [dict(bytes=len(f), sha256=h) for f,h in zip(files,hashes)]
        check('twelveConcurrentCompleteTransfersHaveIdenticalExactOriginals', len(files) == 12 and len(set(hashes)) == 1)
        after = snapshot()
        observations.update(before=before, after=after)
        check('concurrencyCreatesExactlyOneNewReceipt', module.pg(receipt_sql) == 1)
        check('sourceAndEntryCreditsRemainExactlyUnchanged', after == before)
        check('creditStillReferencesItsOriginalActualReceipt', after['unsupported'] == 0)
        status = 'PASS_REAL_HTTP_ENTRY_CONTRIBUTION_REPLAY'
    except Exception as failure:
        status = 'FAIL_REAL_HTTP_ENTRY_CONTRIBUTION_REPLAY'
        observations['failure'] = dict(type=type(failure).__name__, message=str(failure))
    state = json.loads((ROOT/'.tmp/target/process.json').read_text())
    report = dict(status=status, actualOperationTime=datetime.now(timezone.utc).isoformat(),
                  jarSha256=state['jarSha256'], session=args.session, checks=checks, observations=observations,
                  boundary='Actual persisted browser answer, normal API regenerated package, HEAD/Range and twelve real HTTP ZIP transfers. SQL inspection is read-only. No model query, task completion or approval state is manufactured.')
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    args.output.with_suffix('.sql').write_text('-- Read-only inspections; package generation/transfers use normal APIs.\n'+'\n'.join(sql_queries)+'\n')
    print(json.dumps(dict(status=status,passed=sum(checks.values()),failed=[k for k,v in checks.items() if not v]),ensure_ascii=False))
    if status.startswith('FAIL'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
