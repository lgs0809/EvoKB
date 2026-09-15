#!/usr/bin/env python3
"""Cross-check a real browser query against current authenticated APIs and its PostgreSQL ledger."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--account', choices=['admin', 'public', 'confidential'], default='confidential')
    parser.add_argument('--expect', choices=['ANSWERED', 'INSUFFICIENT'], required=True)
    parser.add_argument('--expect-fact', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    uuid.UUID(args.session)
    if args.output.exists():
        raise ValueError('Preserve every attempt by using a new output path')
    accounts = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())
    request, _ = module.client(accounts[args.account])
    status, answer = request('/query/session?id=' + args.session)
    sql = f"""
SELECT json_build_object(
 'session',(SELECT json_build_object('session_id',session_id,'user_id',user_id,'status',status,'question',original_question,'answer',final_answer,'evidence_only',evidence_only,'created_at',created_at,'updated_at',updated_at) FROM query_session WHERE session_id='{args.session}'),
 'unreservedCompletedRounds',(SELECT count(*) FROM retrieval_round r LEFT JOIN query_retrieval_reservation q ON q.reservation_id=r.round_id WHERE r.session_id='{args.session}' AND q.reservation_id IS NULL),
 'reservations',(SELECT COALESCE(json_agg(t),'[]') FROM (SELECT q.reservation_id,q.round_no,q.input_queries,q.started_at,r.round_id AS completed_round_id,r.decision,r.missing_information,r.next_queries,r.finished_at FROM query_retrieval_reservation q LEFT JOIN retrieval_round r ON r.round_id=q.reservation_id WHERE q.session_id='{args.session}' ORDER BY q.round_no) t),
 'evidence',(SELECT COALESCE(json_agg(t),'[]') FROM (SELECT q.evidence_id,q.first_seen_round,e.version_id,a.security_level FROM query_evidence q JOIN evidence e ON e.evidence_id=q.evidence_id LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE q.session_id='{args.session}' ORDER BY q.evidence_id) t),
 'allModelInputVersions',(SELECT COALESCE(json_agg(t),'[]') FROM (SELECT d.version_id,a.security_level FROM query_source_dependency d LEFT JOIN kb_source_authority a ON a.version_id=d.version_id WHERE d.session_id='{args.session}' ORDER BY d.version_id) t)
);
"""
    args.output.with_suffix('.sql').write_text('-- Read-only verification, no workflow state writes.\n' + sql)
    query = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                            '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                           input=sql, text=True, capture_output=True, timeout=30)
    args.output.with_suffix('.sql.error.txt').write_text(query.stderr)
    if query.returncode != 0:
        args.output.write_text(json.dumps(dict(status='FAIL_REAL_QUERY_SQL_INSPECTION',
            error=query.stderr, actualOperationTime=datetime.now(timezone.utc).isoformat()), indent=2) + '\n')
        query.check_returncode()
    database = json.loads(query.stdout)
    slots = database['reservations']
    checks = {}
    checks['ownedRealBrowserSession'] = database['session']['user_id'] == accounts[args.account]['user_id']
    checks['completedAsExpectedThroughNormalApi'] = status == 200 and answer['status'] == args.expect
    checks['apiAndDatabaseAgreeOnStatus'] = answer.get('status') == database['session']['status']
    checks['savedAnswerDeliveredWithoutRewrite'] = answer.get('answer') == database['session']['answer']
    checks['atMostEightConsumedRounds'] = len(slots) <= 8 and all(r['round_no'] <= 8 for r in slots)
    checks['uniqueRoundReservations'] = len({r['round_no'] for r in slots}) == len(slots)
    checks['everyCompletedRoundMatchesReservation'] = bool(slots) and database['unreservedCompletedRounds'] == 0 and all(r['completed_round_id'] == r['reservation_id'] for r in slots if r['completed_round_id'])
    checks['reservationPrecedesCompletedRound'] = all(r['started_at'] <= r['finished_at'] for r in slots if r['completed_round_id'])
    citations = answer.get('citations', [])
    evidence_ids = {r['evidence_id'] for r in database['evidence']}
    checks['citationsBelongToPersistedEvidence'] = all(c['evidence']['evidence_id'] in evidence_ids for c in citations)
    checks['evidenceReferencesConsumedRounds'] = all(e['first_seen_round'] in {s['round_no'] for s in slots} for e in database['evidence'])
    checks['allActualInputVersionsRetained'] = {e['version_id'] for e in database['evidence']}.issubset({v['version_id'] for v in database['allModelInputVersions']})
    checks['requiredFactsArePresent'] = all(fact in answer.get('answer', '') for fact in args.expect_fact)
    if args.expect == 'ANSWERED':
        checks['answerIsRealModelOutputWithCitations'] = not answer.get('evidence_only') and bool(citations)
    else:
        checks['missingEvidenceIsNotUserClarification'] = not answer.get('clarification_question') and '不足' in answer.get('answer', '')
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    def instant(value):
        value = re.sub(r'\.(\d+)(?=[+-]|Z$)', lambda m: '.' + m.group(1)[:6].ljust(6, '0'), value)
        return datetime.fromisoformat(value.replace('Z', '+00:00'))
    earlier_process = instant(database['session']['created_at']) < instant(state['startedAt'])
    report = dict(status='PASS_REAL_QUERY_DURABLE_BUDGET' if all(checks.values()) else 'FAIL_REAL_QUERY_DURABLE_BUDGET',
                  checks=checks, database=database, apiResponse=answer,
                  jarSha256=state['jarSha256'], queryProvenance='HISTORICAL_QUERY_REPLAY' if earlier_process else 'CREATED_DURING_CURRENT_PROCESS', actualOperationTime=datetime.now(timezone.utc).isoformat(),
                  boundary='An actual browser query cross-checked through the current process, saved answer and persisted reservation/evidence records. Historical replay is labelled and does not certify a new model execution on the current JAR. The eight-round ceiling and crash/concurrency proof are separate component reports; this does not claim eight real LLM rounds were needed or classify a dependency interruption as knowledge absence.')
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], sessionId=args.session, passed=sum(checks.values()),
                          failed=[k for k,v in checks.items() if not v]),ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
