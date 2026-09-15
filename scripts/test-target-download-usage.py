#!/usr/bin/env python3
"""Normal authenticated HTTP downloads of actual CUA answers, with read-only PostgreSQL checks."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('permissions', ROOT/'scripts/test-target-permissions.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--second-session', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for session in [args.session, args.second_session]: uuid.UUID(session)
    if args.output.exists(): raise ValueError('Use a fresh output and preserve failed attempts')
    accounts = json.loads((ROOT/'.tmp/target/permission-accounts.json').read_text())
    request, opener = module.client(accounts['confidential'])
    foreign, _ = module.client(accounts['public'])
    checks, observations, sql_queries = {}, [], []
    def check(name, value):
        checks[name] = bool(value)
        if not value: raise AssertionError(name)
    def pg(sql):
        sql_queries.append(sql)
        return module.pg(sql)
    def snapshot():
        return pg("SELECT json_build_object('receipts',(SELECT count(*) FROM package_download_receipt),'contributions',(SELECT count(*) FROM source_usage_contribution),'sources',(SELECT COALESCE(json_agg(t),'[]') FROM (SELECT user_id,session_id,document_id,version_id,receipt_id FROM source_usage_contribution ORDER BY user_id,session_id,version_id) t));")
    def settled(expected, package_id=None):
        for attempt in range(20):
            state = snapshot()
            found = package_id is None or pg("SELECT count(*) FROM package_download_receipt WHERE package_id='"+package_id+"';") == 1
            if state['contributions'] == expected and found: return state
            time.sleep(.2)
        return state
    def download(published, save=False):
        with opener.open(urllib.parse.urljoin(module.BASE,published['download_url']), timeout=30) as response:
            content = response.read()
            if response.status != 200 or len(content) != int(response.headers['Content-Length']):
                raise AssertionError('actual full ZIP response length')
        if save: args.output.with_suffix('.zip').write_bytes(content)
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            for source in manifest['versions']:
                expected = ROOT/'deploy/acceptance/target-fixtures'/source['original_filename']
                if archive.read(source['archive_path']) != expected.read_bytes():
                    raise AssertionError('original file byte mismatch')
        return dict(packageId=published['package_id'],zipSha256=hashlib.sha256(content).hexdigest(),bytes=len(content),manifest=manifest)
    try:
        answers = []
        for session in [args.session,args.second_session]:
            status, answer = request('/query/session?id='+session)
            check('realOwnedTerminalAnswer_'+session,status==200 and answer['status'] in ['ANSWERED','INSUFFICIENT'] and bool(answer['citations']))
            answers.append(answer)
        evidence = [c['evidence']['evidence_id'] for c in answers[0]['citations']]
        baseline = snapshot()
        status,published = request('/package',dict(session_id=args.session,evidence_ids=evidence))
        check('normalAnswerPackageCreated',status==200 and published['manifest']['answers'][0]['session_id']==args.session)
        check('packageCreationHasNoUsageCredit',snapshot()==baseline)
        url=urllib.parse.urljoin(module.BASE,published['download_url'])
        with opener.open(urllib.request.Request(url,method='HEAD'),timeout=30) as response:
            check('headRespondsWithoutBody',response.status==200 and not response.read())
        check('headNeverCreatesReceiptOrContribution',snapshot()==baseline)
        try:
            opener.open(urllib.request.Request(url,headers={'Range':'bytes=0-15'}),timeout=30)
            check('rangeRejected',False)
        except urllib.error.HTTPError as error: check('rangeRejected',error.code==416)
        check('rangeNeverCreatesReceiptOrContribution',snapshot()==baseline)
        status,_=foreign('/package',dict(session_id=args.session,evidence_ids=evidence))
        check('foreignAnswerAttributionRejected',status==403)
        status,_=request('/package',dict(session_id=str(uuid.uuid4()),evidence_ids=evidence))
        check('missingAnswerCannotBeInvented',status==403)
        extras=set(evidence)-{c['evidence']['evidence_id'] for c in answers[1]['citations']}
        if extras:
            status,_=request('/package',dict(session_id=args.second_session,evidence_ids=[next(iter(extras))]))
            check('uncitedEvidenceCannotClaimAnswerHeat',status==400)
        versions={c['evidence']['version_id'] for c in answers[0]['citations']}
        existing={s['version_id'] for s in baseline['sources'] if s['session_id']==args.session}
        expected=baseline['contributions']+len(versions-existing)
        observations.append(download(published,True))
        state=settled(expected,published['package_id'])
        check('completeZipCreatesOneReceipt',pg("SELECT count(*) FROM package_download_receipt WHERE package_id='"+published['package_id']+"';")==1)
        check('fullTransferCreditsEachSourceVersionOnce',state['contributions']==expected)
        with ThreadPoolExecutor(max_workers=6) as pool: repeated=list(pool.map(lambda _:download(published),range(12)))
        check('twelveActualConcurrentZipTransfersAreIdentical',len({r['zipSha256'] for r in repeated})==1)
        check('repeatedDownloadsDoNotDuplicateReceiptOrContributions',settled(expected,published['package_id'])==state)
        status,regenerated=request('/package',dict(session_id=args.session,evidence_ids=evidence))
        check('newPackageForSameAnswerCreated',status==200 and regenerated['package_id']!=published['package_id'])
        observations.append(download(regenerated))
        check('regeneratedZipDoesNotDuplicateAnswerUsage',settled(expected,regenerated['package_id'])['contributions']==expected)
        status,generic=request('/package',dict(evidence_ids=evidence));check('unattributedSearchPackageStillUsable',status==200)
        observations.append(download(generic))
        check('unattributedDownloadCreatesNoAnswerContribution',settled(expected,generic['package_id'])['contributions']==expected)
        status,record=request('/query/history/record/package',dict(session_id=args.second_session,regenerate=False))
        check('historyRecordPackageUsesAnswerAttribution',status==200 and record['status']=='AVAILABLE')
        second_versions={c['evidence']['version_id'] for c in answers[1]['citations']}
        existing={s['version_id'] for s in state['sources'] if s['session_id']==args.second_session}
        expected+=len(second_versions-existing)
        observations.append(download(record))
        check('recordDownloadCreditsSecondActualAnswer',settled(expected,record['package_id'])['contributions']==expected)
        status,group=request('/query/groups',dict(name='下载贡献验收-'+uuid.uuid4().hex[:8],session_ids=[args.session,args.second_session]))
        check('actualQueryGroupCreated',status==200)
        status,group_package=request('/query/group/package',dict(group_id=group['group_id'],regenerate=False))
        check('actualGroupedAnswerPackageCreated',status==200 and group_package['status']=='AVAILABLE')
        grouped=download(group_package);observations.append(grouped)
        check('groupManifestKeepsTwoAnswerAttributions',{a['session_id'] for a in grouped['manifest']['answers']}=={args.session,args.second_session})
        check('groupedDownloadDoesNotRechargeSameAnswers',settled(expected,group_package['package_id'])['contributions']==expected)
        status,overview=request('/overview');check('overviewShowsCurrentThirtyDaySourceContributions',status==200 and overview['effective_hits']>=expected)
        result_status='PASS_REAL_HTTP_COMPLETED_DOWNLOAD_USAGE'
    except Exception as failure:
        result_status='FAIL_REAL_HTTP_COMPLETED_DOWNLOAD_USAGE'
        observations.append(dict(failureType=type(failure).__name__,failure=str(failure)))
    state=json.loads((ROOT/'.tmp/target/process.json').read_text())
    report=dict(status=result_status,checks=checks,observations=observations,jarSha256=state['jarSha256'],actualOperationTime=datetime.now(timezone.utc).isoformat(),
                boundary='Normal HTTP APIs and actual ZIP byte transfers for persisted CUA answers. Read-only SQL only. Query group is a retained test group; no manual success state writes. HEAD/range, replay and concurrency are actual HTTP; abrupt client disconnect, entry provenance and selective compilation are not certified here.')
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    args.output.with_suffix('.sql').write_text('-- All inspections below are read-only.\n'+'\n'.join(sql_queries)+'\n')
    print(json.dumps(dict(status=result_status,passed=sum(checks.values()),failed=[k for k,v in checks.items() if not v]),ensure_ascii=False))
    if result_status.startswith('FAIL'): raise SystemExit(1)


if __name__=='__main__': main()
