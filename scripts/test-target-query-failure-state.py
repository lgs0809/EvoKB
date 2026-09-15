#!/usr/bin/env python3
"""Inspect an existing interrupted query without causing failure or changing its state."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--account', choices=['admin', 'public', 'confidential'], default='admin')
    parser.add_argument('--expected-reservations', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    session = str(uuid.UUID(args.session))
    output = args.output.resolve()
    if any(output.with_suffix(s).exists() for s in ['.json', '.sql', '.log']):
        raise ValueError('Preserve every previous attempt; use a fresh output stem')
    output.parent.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    account = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())[args.account]
    request, _ = module.client(account)
    code, response = request('/query/session?id=' + session)
    sql = f"""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'session',(SELECT to_jsonb(s) FROM query_session s WHERE session_id='{session}'),
 'turns',(SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') FROM query_turn t WHERE session_id='{session}'),
 'reservations',(SELECT coalesce(jsonb_agg(to_jsonb(r)),'[]') FROM query_retrieval_reservation r WHERE session_id='{session}'),
 'rounds',(SELECT coalesce(jsonb_agg(to_jsonb(r)),'[]') FROM retrieval_round r WHERE session_id='{session}'),
 'sourceCredits',(SELECT count(*) FROM source_usage_contribution WHERE session_id='{session}'),
 'entryCredits',(SELECT count(*) FROM asset_usage_contribution WHERE session_id='{session}')
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
    interrupted = data['session']
    reserved = data['reservations']
    checks = {
        'normalOwnedSessionReadable': code == 200 and interrupted['user_id'] == account['user_id'],
        'technicalInterruptionIsFailedInsteadOfKnowledgeInsufficient':
            response.get('status') == interrupted['status'] == 'FAILED',
        'nativeDependencyEventRetained': any(t['content'] == 'DEPENDENCY_UNAVAILABLE' for t in data['turns']),
        'savedAnswerDeliveredWithoutRewrite': response.get('answer') == interrupted['final_answer'],
        'interruptionDoesNotClaimKnowledgeAbsence':
            '暂时不可用' in interrupted['final_answer'] and '证据不足' not in interrupted['final_answer']
            and '不能据此认定所需资料不存在' in interrupted['final_answer'],
        'alreadyDispatchedBudgetNotRefunded': len(reserved) == args.expected_reservations
            and len({r['reservation_id'] for r in reserved}) == len(reserved)
            and len({r['round_no'] for r in reserved}) == len(reserved)
            and all(1 <= r['round_no'] <= 8 for r in reserved),
        'completedRoundsStillUseExistingReservations':
            all(r['round_id'] in {s['reservation_id'] for s in reserved} for r in data['rounds']),
        'interruptionDoesNotManufactureDownloadHeat': data['sourceCredits'] == data['entryCredits'] == 0,
    }
    report = dict(status='PASS_ACTUAL_QUERY_FAILURE_STATE' if all(checks.values()) else 'FAIL_ACTUAL_QUERY_FAILURE_STATE',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(), checks=checks,
                  process=json.loads((ROOT / '.tmp/target/process.json').read_text()), native=data,
                  normalResponse=response,
                  scope='Existing normal query, authenticated GET and read-only PG. No new failure, inference, '
                        'status transition, budget refund or usage state is manufactured. '
                        'Not a successful answer, a model-quality score or a claim about the exact failed dependency.')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()),
                         failed=[k for k, v in checks.items() if not v])))
    raise SystemExit(int(not all(checks.values())))


if __name__ == '__main__':
    main()
