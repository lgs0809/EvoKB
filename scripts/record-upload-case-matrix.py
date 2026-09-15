#!/usr/bin/env python3
"""Inventory the guide's 12 upload cases against existing actual evidence; no test execution."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--evidence-dir', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh matrix required; preserve previous pending/failure inventory')
refs = {
    'api': 'raw-multipart-api-2.json',
    'native': 'raw-multipart-native-3.json',
    'url': 'raw-multipart-native-2.json',
    'real_crash': 'multipart-real-crash-native-2.json',
    'cua_pdf': 'raw-browser-controlled-pdf-1/readback-1.json',
    'cua_resume': 'raw-browser-multipart-resume-1/result-1.json',
}
loaded = {}
for alias, relative in refs.items():
    path = a.evidence_dir / relative
    if path.exists():
        loaded[alias] = json.loads(path.read_text())


def evidence(alias, *checks):
    relative = refs[alias]
    data = loaded.get(alias)
    good = bool(data and str(data.get('status', '')).startswith('PASS'))
    if checks:
        good = good and all(data.get('checks', {}).get(key) is True for key in checks)
    return {
        'reference': str((a.evidence_dir / relative).resolve()),
        'actualStatus': data.get('status') if data else 'NOT_YET_RECORDED',
        'checks': list(checks),
        'proven': good,
        'artifactBinding': ({key: data[key] for key in ['jarSha256', 'deployedArtifact', 'dependencyJarSha256', 'sourceHashes', 'provenance', 'deploymentClaim'] if key in data} if data else {}),
    }


definitions = [
    ('UP-01', '正常上传', 'ACTUAL_CUA_PARSER_MODEL_PG_MINIO_MILVUS',
     [evidence('cua_pdf')], 'Existing real PDF normal browser upload and actual full Raw readback; not a fresh parser/model rerun by this inventory.'),
    ('UP-02', '短末片', 'ACTUAL_NATIVE_AND_CUA',
     [evidence('native', 'actualFinalShortPartAccepted'), evidence('cua_resume')], 'Native 93-byte tail and normal 67MiB fixture final five-part manifest; transport padding is explicitly synthetic.'),
    ('UP-03', '断线恢复', 'ACTUAL_TCP_NATIVE_COMPONENT',
     [evidence('real_crash', 'actualPartialTcpDisconnectLeavesNoCompletedPart', 'sameSessionRealRetransferAndShortTailSucceed', 'tcpRecoveryMergesExactWholeOriginalOnce')], 'Real socket closes after 65536 of 16777216 declared bytes, then the same session transfers and merges exact original SHA. No claim of new browser disconnect or model parsing.'),
    ('UP-04', '重选核验', 'ACTUAL_API_AND_CUA',
     [evidence('api', 'reject-wrong-reselected-sha'), evidence('cua_resume')], 'Different full SHA rejected; same original/meta resumed in normal browser using an API-prepared two-part session. Initial exact browser PUT count remains unverified.'),
    ('UP-05', '重复完成', 'ACTUAL_NORMAL_API_AND_NATIVE_CONCURRENCY',
     [evidence('api', 'normalDuplicateCompleteReusesSameVersionAndTask', 'normalConcurrencyCreatesExactlyOneReservedVersion'), evidence('native', 'twentyFourConcurrentCompletionsCreateOneTaskAndVersion')], 'Normal concurrent complete requests and native 24-way completion retain one version/task; completed transport does not claim parsed success.'),
    ('UP-06', '合并后崩溃', 'ACTUAL_PROCESS_CRASH_AND_COMPLETION600_NATIVE',
     [evidence('real_crash', 'actualSeparateMergeOwnerHaltedAfterNativeObjectBeforeDbFinalize', 'fullActualCompletion600PreservesOwnerWithoutRenewalOrEarlyFinalize', 'newOwnerRecoversMergedObjectByHeadExactlyOnce', 'recoveryReplayKeepsSameVersionTaskAndPreexistingCompletedOriginal')], 'Separate JVM halt71 after actual MinIO merge, actual600 wait, then guarded HEAD finalization. No DB deadline mutation and no manufactured parse/index success.'),
    ('UP-07', 'URL到期与补签', 'ACTUAL_NATIVE_WALL_TIME',
     [evidence('url', 'actual600SecondSignatureExpiresWithoutExpiring24hSession', 'expiredUrlReauthenticatedAndRenewed')], 'Actual603.9-second wait, old URL403 and reauthenticated new URL200 on the same live24h session; prior report errata excludes an unrelated insufficient Raw assertion.'),
    ('UP-08', '越权', 'ACTUAL_NORMAL_API_AND_NATIVE',
     [evidence('api', 'anotherAuthorizedUploaderCannotReadSession'), evidence('native', 'otherUploaderCannotReadOrSignOwnedSession')], 'Another authorized uploader cannot view/sign this session. Does not certify a full multi-tenant model.'),
    ('UP-09', '校验不符', 'ACTUAL_STORAGE_AND_NORMAL_RAW_WORKER',
     [evidence('api', 'actualNativeStorageRejectsWrongPartMd5', 'normalWorkerRejectsWrongWholeShaBeforeModelOrPublication')], 'Native bad partMD5 rejected and normal wrong wholeSHA becomes INPUT_ERROR before model/publication, preserving actual digest.'),
    ('UP-10', '分片缺失', 'ACTUAL_NORMAL_API_AND_NATIVE',
     [evidence('api', 'reject-missing-parts-before-completion'), evidence('native', 'missingPartCannotEnterCompleting')], 'Incomplete manifests remain UPLOADING and cannot finalize.'),
    ('UP-11', '取消', 'ACTUAL_NORMAL_API_AND_NATIVE_MINIO',
     [evidence('api', 'normalCancelAbortsOwnedMultipart'), evidence('native', 'cancelAbortsOnlyOwnedIncompleteUpload')], 'Only the owned unfinished multipart is aborted; existing original remains intact.'),
    ('UP-12', '过期清理', 'ACTUAL_NATIVE_MINIO_WITH_ISOLATED_EXPIRY_FIXTURE',
     [evidence('native', 'hourlyCleanerAbortsExpiredFixtureAndPreservesOtherOriginal')], 'Expired session boundary injected only in isolated PG, native cleaner actually aborts its multipart and preserves another original. Not a real24h wait or an observed production hourly trigger.'),
]
cases = [{'id': ident, 'case': title, 'verificationLevel': level,
          'status': 'PASS_SCOPED_MECHANISM' if all(item['proven'] for item in items) else 'PENDING_OR_FAILED',
          'evidence': items, 'limits': limits}
         for ident, title, level, items, limits in definitions]
report = {
    'status': 'PASS_12_SCOPED_UPLOAD_CASE_MECHANISMS' if all(case['status'].startswith('PASS') for case in cases) else 'PENDING_UPLOAD_CASE_MATRIX',
    'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'specification': 'Latest 项目全解-EvoKB.md §3.A: 正常/末片2、断线/重选2、重复完成/合并后崩溃2、URL过期/越权2、校验不符/分片缺失2、取消/过期清理2.',
    'cases': cases,
    'boundary': 'This inventory maps previously executed evidence with exact levels/artifact provenance. It runs no tests, adds no assertion counts, and does not certify all12 as browser flows, the same deployedJar rerun, real24h elapsed, production hourly timing, or the240-question main evaluation/5.1→1.8 historical result.',
    'unverified': ['Exact first browser continuation PUT3/4/5 trace count', 'Real24h session wait/production hourly-trigger timing', 'Full240 fixed evaluation and scan/multi-page/cross-page table coverage are separate'],
    'evidenceHashes': {str(a.evidence_dir / relative): hashlib.sha256((a.evidence_dir / relative).read_bytes()).hexdigest()
                       for relative in refs.values() if (a.evidence_dir / relative).exists()},
}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({'status': report['status'], 'provenCases': sum(case['status'].startswith('PASS') for case in cases),
                  'pending': [case['id'] for case in cases if not case['status'].startswith('PASS')]}, ensure_ascii=False))
