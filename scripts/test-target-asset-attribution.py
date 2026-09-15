#!/usr/bin/env python3
"""Cross-check one actual CUA answer/download against its immutable PG entry attribution."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--session', required=True)
    p.add_argument('--download-path', type=Path, required=True)
    p.add_argument('--account', choices=['admin', 'public', 'confidential'], default='admin')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    uuid.UUID(args.session)
    if args.output.exists():
        raise ValueError('Preserve every attempt; use a fresh output')
    spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    account = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())[args.account]
    request, _ = module.client(account)
    code, answer = request('/query/session?id=' + args.session)
    sql = f"""
SELECT json_build_object(
 'session',(SELECT json_build_object('status',status,'created_at',created_at,'updated_at',updated_at,'question',original_question,'answer',final_answer) FROM query_session WHERE session_id='{args.session}'),
 'expansions',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT x.asset_id,x.asset_version,x.source_version_id,x.evidence_id,x.first_seen_round,s.evidence_ids @> jsonb_build_array(x.evidence_id) AS supported FROM query_asset_expansion x JOIN kb_asset_source s USING(asset_id,asset_version) WHERE x.session_id='{args.session}' AND s.source_version_id=x.source_version_id) t),
 'contributions',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT user_id,session_id,asset_id,asset_version,receipt_id FROM asset_usage_contribution WHERE session_id='{args.session}') t),
 'sourceContributions',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT document_id,version_id,receipt_id FROM source_usage_contribution WHERE session_id='{args.session}') t),
 'packages',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT a.package_id,a.version_id,p.size_bytes,r.receipt_id,r.transferred_bytes FROM package_answer_source a JOIN package_usage_manifest p USING(package_id) LEFT JOIN package_download_receipt r USING(package_id) WHERE a.session_id='{args.session}') t),
 'rounds',(SELECT coalesce(json_agg(t),'[]') FROM (SELECT round_no,input_queries,decision FROM retrieval_round WHERE session_id='{args.session}') t),
 'unexpandedCatalogueEntriesCredited',(SELECT count(*) FROM asset_usage_contribution u WHERE u.session_id='{args.session}' AND NOT EXISTS(SELECT 1 FROM query_asset_expansion x WHERE x.session_id=u.session_id AND x.asset_id=u.asset_id)),
 'unsupportedCredit',(SELECT count(*) FROM asset_usage_contribution u WHERE u.session_id='{args.session}' AND NOT EXISTS(SELECT 1 FROM query_asset_expansion x JOIN package_answer_source a ON a.session_id=x.session_id AND a.version_id=x.source_version_id JOIN package_download_receipt r ON r.package_id=a.package_id WHERE x.session_id=u.session_id AND x.asset_id=u.asset_id AND r.receipt_id=u.receipt_id))
);
"""
    args.output.with_suffix('.sql').write_text('-- Read-only normal browser acceptance inspections.\n' + sql)
    result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                             '-v', 'ON_ERROR_STOP=1', '-U', 'evokb_target', '-d', 'evokb_target'],
                            input=sql, text=True, capture_output=True, check=True, timeout=30)
    observed = json.loads(result.stdout)
    content = args.download_path.read_bytes()
    with zipfile.ZipFile(args.download_path) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        originals = [dict(filename=v['original_filename'], version=v['version_id'],
                          exactBytes=archive.read(v['archive_path']) == (ROOT / 'deploy/acceptance/target-fixtures' / v['original_filename']).read_bytes())
                     for v in manifest['versions']]
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    created = datetime.fromisoformat(observed['session']['created_at'])
    expansions = observed['expansions']
    packaged = {v['version_id'] for v in manifest['versions']}
    expected = {x['asset_id'] for x in expansions if x['source_version_id'] in packaged}
    actual = {c['asset_id'] for c in observed['contributions']}
    original_receipts = {c['receipt_id'] for c in observed['contributions']}
    credited_packages = [p for p in observed['packages'] if p['receipt_id'] in original_receipts]
    text = observed['session']['answer']
    checks = {
        'currentOwnedPersistedAnswerReadable': code == 200 and answer.get('status') == 'ANSWERED',
        'newBrowserQueryCreatedDuringCurrentDeployment': created > datetime.fromisoformat(state['startedAt']),
        'warrantyAndResponseFactsPresent': all(re.search(pattern, text) for pattern in ['验收通过', '24\\s*个?月', '4\\s*小时', '48\\s*小时', '违规操作']),
        'actualEntryExpandedWithSupportedRawEvidence': bool(expansions) and all(x['supported'] for x in expansions),
        'singleCompletedRound': len(observed['rounds']) == 1 and observed['rounds'][0]['decision'] == 'ANSWER',
        'nativeZipContainsExactSeedOriginalBytes': bool(originals) and all(o['exactBytes'] for o in originals),
        'zipKeepsActualAnswerAttribution': {a['session_id'] for a in manifest['answers']} == {args.session},
        'actualCompletedTransferReceiptMatchesNativeZipBytes': len(credited_packages) == 1 and credited_packages[0]['transferred_bytes'] == len(content) == credited_packages[0]['size_bytes'],
        'onlyExpandedPackagedEntriesCredited': bool(expected) and expected == actual and len(actual) == len(observed['contributions']),
        'otherEntriesOfSameDocumentReceiveNoBroadcastHeat': observed['unexpandedCatalogueEntriesCredited'] == 0,
        'everyCreditHasExactExpandedSourceInItsActualReceipt': observed['unsupportedCredit'] == 0,
        'entryAndSourceCreditShareActualDownloadReceipt': {c['receipt_id'] for c in observed['contributions']} == {c['receipt_id'] for c in observed['sourceContributions']},
    }
    report = dict(status='PASS_REAL_BROWSER_ENTRY_DOWNLOAD_ATTRIBUTION' if all(checks.values()) else 'FAIL_REAL_BROWSER_ENTRY_DOWNLOAD_ATTRIBUTION',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(), jarSha256=state['jarSha256'], checks=checks,
                  observed=observed, nativeDownloadPath=str(args.download_path), zipSha256=hashlib.sha256(content).hexdigest(),
                  originals=originals, manifest=manifest,
                  boundary='Actual CUA natural-language query, raw preview and native ZIP download. Read-only SQL and normal owned APIs; no synthetic answer/task state writes. Does not certify automatic entry matching, promotion, daily quotas or Graph.')
    shutil.copyfile(args.download_path, args.output.with_suffix('.zip'))
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()), failed=[k for k,v in checks.items() if not v]), ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
