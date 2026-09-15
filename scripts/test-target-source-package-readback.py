#!/usr/bin/env python3
"""Cross-check an existing browser download with originals and native usage receipts."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--download-path', type=Path, required=True)
    parser.add_argument('--fixture-root', type=Path, required=True)
    parser.add_argument('--account', choices=['admin', 'public', 'confidential'], default='admin')
    parser.add_argument('--execution-proof', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    session = str(uuid.UUID(args.session))
    output = args.output.resolve()
    if any(output.with_suffix(suffix).exists() for suffix in ['.json', '.sql', '.log', '.zip']):
        raise ValueError('Use a fresh output stem; preserve prior observations')
    output.parent.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    client = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(client)
    account = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())[args.account]
    request, _ = client.client(account)
    code, answer = request('/query/session?id=' + session)
    sql = f"""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'session',(SELECT to_jsonb(s) FROM query_session s WHERE session_id='{session}'),
 'expansions',(SELECT coalesce(jsonb_agg(t),'[]') FROM (SELECT x.*,
   s.evidence_ids @> jsonb_build_array(x.evidence_id) AS supported
   FROM query_asset_expansion x JOIN kb_asset_source s USING(asset_id,asset_version)
   WHERE x.session_id='{session}' AND s.source_version_id=x.source_version_id) t),
 'entryCredits',(SELECT coalesce(jsonb_agg(t),'[]') FROM
   (SELECT * FROM asset_usage_contribution WHERE session_id='{session}') t),
 'sourceCredits',(SELECT coalesce(jsonb_agg(t),'[]') FROM
   (SELECT * FROM source_usage_contribution WHERE session_id='{session}') t),
 'packages',(SELECT coalesce(jsonb_agg(t),'[]') FROM (SELECT DISTINCT
   a.package_id,p.size_bytes,r.receipt_id,r.transferred_bytes
   FROM package_answer_source a JOIN package_usage_manifest p USING(package_id)
   LEFT JOIN package_download_receipt r USING(package_id) WHERE a.session_id='{session}') t),
 'versions',(SELECT coalesce(jsonb_agg(t),'[]') FROM (SELECT DISTINCT
   v.version_id,v.document_id,v.original_filename,v.sha256,
   a.acl_version,a.security_level
   FROM package_answer_source p JOIN document_version v USING(version_id)
   LEFT JOIN kb_source_authority a USING(version_id) WHERE p.session_id='{session}') t),
 'rounds',(SELECT coalesce(jsonb_agg(t),'[]') FROM
   (SELECT round_no,decision FROM retrieval_round WHERE session_id='{session}') t),
 'unsupportedEntryCredit',(SELECT count(*) FROM asset_usage_contribution u
   WHERE u.session_id='{session}' AND NOT EXISTS(
     SELECT 1 FROM query_asset_expansion x JOIN package_answer_source a
       ON a.session_id=x.session_id AND a.version_id=x.source_version_id
     JOIN package_download_receipt r ON r.package_id=a.package_id
     WHERE x.session_id=u.session_id AND x.asset_id=u.asset_id
       AND x.asset_version=u.asset_version AND r.receipt_id=u.receipt_id))
);
COMMIT;
"""
    output.with_suffix('.sql').write_text(sql)
    native = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                             '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, text=True, capture_output=True, timeout=30)
    output.with_suffix('.log').write_text(native.stdout + native.stderr)
    native.check_returncode()
    data = json.loads(next(line for line in native.stdout.splitlines() if line.startswith('{')))
    content = args.download_path.read_bytes()
    versions = {v['version_id']: v for v in data['versions']}
    fixture_root = args.fixture_root.resolve()
    originals = []
    with zipfile.ZipFile(args.download_path) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        for item in manifest['versions']:
            fixture = (fixture_root / item['original_filename']).resolve()
            if not fixture.is_relative_to(fixture_root):
                raise ValueError('Fixture filename must remain inside fixture root')
            raw = archive.read(item['archive_path'])
            authoritative = versions[item['version_id']]
            originals.append(dict(
                version=item['version_id'], filename=item['original_filename'], bytes=len(raw),
                sha256=hashlib.sha256(raw).hexdigest(),
                exactFixtureBytes=raw == fixture.read_bytes(),
                matchesNativeSha256=hashlib.sha256(raw).hexdigest() == authoritative['sha256'],
                matchesNativeIdentity=item['document_id'] == authoritative['document_id']
                    and item['original_filename'] == authoritative['original_filename'],
                currentAuthority=authoritative['acl_version'] is not None))
    execution = json.loads(args.execution_proof.read_text())['process']
    reader = json.loads((ROOT / '.tmp/target/process.json').read_text())
    packaged = {v['version_id'] for v in manifest['versions']}
    expected = {(x['asset_id'], x['asset_version']) for x in data['expansions']
                if x['source_version_id'] in packaged}
    credited = {(x['asset_id'], x['asset_version']) for x in data['entryCredits']}
    source_receipts = {x['receipt_id'] for x in data['sourceCredits']}
    entry_receipts = {x['receipt_id'] for x in data['entryCredits']}
    actual_packages = [p for p in data['packages'] if p['receipt_id'] in source_receipts]
    checks = {
        'normalOwnedAnsweredSession': code == 200 and answer.get('status') == 'ANSWERED'
            and data['session']['user_id'] == account['user_id'],
        'queryCreatedAfterCapturedExecutionDeployment':
            datetime.fromisoformat(data['session']['created_at']) > datetime.fromisoformat(execution['startedAt']),
        'persistedCitationIdsEqualPackagedCitationIds':
            set(data['session']['citation_evidence_ids']) == set(manifest['evidence_ids']),
        'manifestAttributesOnlyThisActualAnswer':
            {a['session_id'] for a in manifest['answers']} == {session}
            and all(set(a['evidence_ids']) <= set(manifest['evidence_ids']) for a in manifest['answers']),
        'packageVersionsMatchNativeAnswerSources': packaged == set(versions),
        'originalsMatchExactFixtures': bool(originals) and all(o['exactFixtureBytes'] for o in originals),
        'originalsMatchNativeSourceHashes': bool(originals) and all(o['matchesNativeSha256'] for o in originals),
        'originalFilenamesAndDocumentIdentitiesMatchNative':
            bool(originals) and all(o['matchesNativeIdentity'] for o in originals),
        'allPackagedSourcesHaveCurrentAuthority': bool(originals) and all(o['currentAuthority'] for o in originals),
        'completedReceiptMatchesActualZipBytes': len(actual_packages) == 1
            and actual_packages[0]['transferred_bytes'] == actual_packages[0]['size_bytes'] == len(content),
        'eachActualPackagedDocumentCreditedOnce':
            {x['document_id'] for x in data['sourceCredits']} == {v['document_id'] for v in manifest['versions']}
            and len(data['sourceCredits']) == len({x['document_id'] for x in data['sourceCredits']}),
        'onlyExpandedPackagedEntryRevisionsCredited':
            bool(expected) and expected == credited and len(credited) == len(data['entryCredits']),
        'expansionsHaveAuthoritativeRawEvidence':
            bool(data['expansions']) and all(x['supported'] for x in data['expansions']),
        'entryAndSourceHeatShareActualTransferReceipt': len(source_receipts) == 1 and source_receipts == entry_receipts,
        'noUnsupportedOrBroadcastEntryHeat': data['unsupportedEntryCredit'] == 0,
        'completedRetrievalHasAnswerDecision': bool(data['rounds']) and data['rounds'][-1]['decision'] == 'ANSWER',
    }
    shutil.copyfile(args.download_path, output.with_suffix('.zip'))
    report = dict(status='PASS_ACTUAL_SOURCE_PACKAGE_READBACK' if all(checks.values()) else 'FAIL_ACTUAL_SOURCE_PACKAGE_READBACK',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(), checks=checks,
                  executionArtifact=execution, readerArtifact=reader, native=data,
                  originals=originals, manifest=manifest, zipSha256=hashlib.sha256(content).hexdigest(),
                  scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  boundary='Existing real CUA answer and downloaded ZIP; normal owned GET and read-only PostgreSQL. '
                           'No new inference, usage, task, publication or approval state is manufactured. '
                           'This certifies bytes and attribution, not answer quality, Wiki thresholds, Graph or formal model evaluation.')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()),
                         failed=[name for name, passed in checks.items() if not passed])))
    raise SystemExit(int(not all(checks.values())))


if __name__ == '__main__':
    main()
