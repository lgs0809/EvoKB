#!/usr/bin/env python3
"""Score an already completed ordinary browser retry without starting a model call."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', type=Path, required=True)
    p.add_argument('--session', required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    sid = str(uuid.UUID(a.session))
    before = json.loads(a.before.read_text())
    old = before['originalSession']
    old_id = str(uuid.UUID(old['session_id']))
    if a.output.exists() or sid == old_id:
        raise ValueError('Preserve reports and the original failed session')
    a.output.parent.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('retry_permission_client', ROOT / 'scripts/test-target-permissions.py')
    client_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(client_module)
    accounts = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())
    owner = next(key for key, value in accounts.items() if value['user_id'] == old['user_id'])
    request = client_module.client(accounts[owner])[0]
    http_status, result = request('/query/session?id=' + sid)
    if http_status != 200 or result.get('status') == 'RUNNING':
        raise ValueError('Wait for the genuine query terminal state; do not interrupt it')
    sql = f"""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'old',(SELECT to_jsonb(s) FROM query_session s WHERE session_id='{old_id}'),
 'session',(SELECT to_jsonb(s) FROM query_session s WHERE session_id='{sid}'),
 'reservations',(SELECT coalesce(jsonb_agg(r ORDER BY round_no),'[]') FROM query_retrieval_reservation r WHERE session_id='{sid}'),
 'rounds',(SELECT coalesce(jsonb_agg(r ORDER BY round_no),'[]') FROM retrieval_round r WHERE session_id='{sid}'),
 'evidence',(SELECT coalesce(jsonb_agg(e),'[]') FROM (SELECT q.evidence_id,e.version_id,a.security_level,a.acl_version
   FROM query_evidence q JOIN evidence e USING(evidence_id)
   LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE q.session_id='{sid}') e),
 'dependencies',(SELECT coalesce(jsonb_agg(d),'[]') FROM (SELECT q.version_id,a.security_level,a.acl_version
   FROM query_source_dependency q LEFT JOIN kb_source_authority a USING(version_id) WHERE q.session_id='{sid}') d),
 'sourceCredits',(SELECT count(*) FROM source_usage_contribution WHERE session_id='{sid}'),
 'entryCredits',(SELECT count(*) FROM asset_usage_contribution WHERE session_id='{sid}'));
COMMIT;
"""
    a.output.with_suffix('.sql').write_text(sql)
    read = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                           '-v', 'ON_ERROR_STOP=1', '-U', 'evokb_target', '-d', 'evokb_target'],
                          input=sql, capture_output=True, text=True, timeout=30, check=True)
    a.output.with_suffix('.sql.log').write_text(read.stdout + read.stderr)
    native = json.loads(next(line for line in read.stdout.splitlines() if line.startswith('{')))
    s, o = native['session'], native['old']
    saved_scope = old['filters']
    snake = [('source_department', 'sourceDepartment'), ('material_category', 'materialCategory'),
             ('document_type', 'documentType'), ('version_ids', 'versionIds')]
    def scope(x):
        return {k: x.get(k, x.get(c)) for k, c in snake}
    manifest_path = ROOT / 'deploy/acceptance/formal-evaluation/manifest.json'
    manifest = json.loads(manifest_path.read_text())
    case = next(c for c in manifest['cases'] if c['question'] == old['question'])
    clearance = 10 if owner == 'public' else 30
    checks = dict(
        normalRetryCreatedNewSession=s['created_at'] > before['observedAt'] and sid != old_id,
        ownedSavedAnswerMatchesApi=s['user_id'] == old['user_id'] and s['status'] == result['status'] and s['final_answer'] == result['answer'],
        originalFailedSessionPreserved=o['status'] == old['status'] == 'FAILED' and o['original_question'] == old['question'],
        exactOriginalNaturalQuestion=s['original_question'] == old['question'] == result['question'],
        allFourOriginalScopeFieldsPreserved=scope(s['filters_json']) == scope(saved_scope) == scope(result['filters']),
        exposedScopeContainsNoTrustedClearance=not {'security_clearance', 'securityClearance'} & result['filters'].keys(),
        budgetRemainsAtMostEight=len(native['reservations']) <= 8 and sum(len(r['input_queries']) for r in native['reservations']) <= 8,
        uniqueReservations=len({r['round_no'] for r in native['reservations']}) == len(native['reservations']),
        completedRoundsHaveReservation=all(r['round_id'] in {v['reservation_id'] for v in native['reservations']} for r in native['rounds']),
        evidenceAndDependenciesAuthorized=all(e['security_level'] is not None and e['security_level'] <= clearance for e in native['evidence'] + native['dependencies']),
        citationsBelongToNativeEvidenceAndSavedScope=all(c['evidence']['evidence_id'] in {e['evidence_id'] for e in native['evidence']} and c['evidence']['version_id'] in scope(saved_scope)['version_ids'] for c in result['citations']),
        noManufacturedDownloadHeat=native['sourceCredits'] == native['entryCredits'] == 0,
        originalFrozenGoldUnchanged=hashlib.sha256(manifest_path.read_bytes()).hexdigest() == before['manifestSha256'],
        expectedSafetyOutcome=result['status'] in case['expectedStatus'],
    )
    report = dict(status='PASS_BROWSER_RETRY' if all(checks.values()) else 'FAIL_BROWSER_RETRY',
                  verifiedAt=datetime.now(timezone.utc).isoformat(), before=before,
                  beforeSha256=hashlib.sha256(a.before.read_bytes()).hexdigest(),
                  verifierSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  caseId=case['caseId'], native=native, normalOwnedApi=result,
                  checks=checks, verifierModelCalls=0, businessSqlMutations=0,
                  boundary='Only reads the already completed browser query. The initiating deployment is bound by before/process; later verifier deployment is not a new inference. Expected status failure stays failure; safety and retry mechanics do not prove availability.')
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()),
                         failed=[k for k, v in checks.items() if not v])))
    raise SystemExit(int(not all(checks.values())))


if __name__ == '__main__':
    main()
