#!/usr/bin/env python3
"""Normal HTTP admission, exact task replay and native readback for an existing marked Wiki fixture."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Use a fresh report path and preserve every attempt')
    spec = importlib.util.spec_from_file_location('permission_client', ROOT/'scripts/test-target-permissions.py')
    client = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(client)
    accounts = json.loads((ROOT/'.tmp/target/permission-accounts.json').read_text())
    admin, _ = client.client(accounts['admin'])
    reader, _ = client.client(accounts['public'])
    checks, observations, queries = {}, {}, []

    def check(name, value):
        checks[name] = bool(value)
        if not value:
            raise AssertionError(name)

    def snapshot(task_id):
        import uuid
        uuid.UUID(task_id)
        sql = """SELECT jsonb_build_object(
          'task',(SELECT to_jsonb(t) FROM compile_task t WHERE task_id='%s'),
          'commands',(SELECT count(*) FROM kb_compilation_command WHERE task_id='%s'),
          'inputs',(SELECT coalesce(jsonb_agg(to_jsonb(i)),'[]'::jsonb) FROM compile_task_input i WHERE task_id='%s'),
          'submission',(SELECT to_jsonb(c)-'input_versions' FROM kb_compilation_command c WHERE task_id='%s')
        );""" % (task_id, task_id, task_id, task_id)
        queries.append(sql)
        return client.pg(sql)

    try:
        status, body = admin('/wikis')
        check('normalAdminReadsPublishedWiki', status == 200)
        wikis = [w for w in body['wikis'] if w['title'] == 'HT-MV-017补充协议' and w['lifecycle'] == 'ACTIVE']
        check('targetIsPreviouslyOwnedFictionalAcceptanceFixture', len(wikis) == 1)
        target = wikis[0]
        request = dict(wiki_id=target['wiki_id'], wiki_version=target['wiki_version'])
        observations['requestedIdentity'] = request
        status, _ = reader('/compilation/wiki-keywords', request)
        observations['ordinaryMemberResponseStatus'] = status
        check('ordinaryMemberCannotSubmitAdminCompilation', status == 403)
        anon = urllib.request.Request(client.BASE+'/api/enterprise/compilation/wiki-keywords',
            data=json.dumps(request).encode(), headers={'Content-Type':'application/json'})
        try:
            with urllib.request.urlopen(anon, timeout=10):
                check('anonymousCannotSubmitCompilation', False)
        except urllib.error.HTTPError as failure:
            check('anonymousCannotSubmitCompilation', failure.code == 401)
        with ThreadPoolExecutor(max_workers=8) as pool:
            replies = list(pool.map(lambda _: admin('/compilation/wiki-keywords', request), range(24)))
        check('twentyFourNormalRequestsAreAcceptedAsDurableTasks', all(s == 202 for s, _ in replies))
        task_ids = {r.get('task_id', r.get('taskId')) for _, r in replies}
        check('allDuplicateRequestsReturnOneTaskIdentity', len(task_ids) == 1 and None not in task_ids)
        task_id = next(iter(task_ids))
        observations['taskId'] = task_id
        deadline = time.monotonic()+60
        state = snapshot(task_id)
        while state['task']['status'] in ('PENDING','RUNNING') and time.monotonic() < deadline:
            time.sleep(2)
            state = snapshot(task_id)
        observations['nativeReadback'] = state
        check('ordinaryWorkerActuallyCompletesOneAttempt', state['task']['status'] == 'SUCCEEDED' and state['task']['attempt'] == 1)
        check('exactActorAndAllWikiArgumentsArePersisted', state['commands'] == 1
            and state['submission']['submitted_by'] == accounts['admin']['user_id']
            and state['submission']['request_json']['kind'] == 'WIKI_KEYWORDS'
            and state['submission']['request_json']['wiki_id'] == request['wiki_id']
            and state['submission']['request_json']['wiki_version'] == request['wiki_version'])
        check('currentSourceInputsWerePinnedForActualExecution', bool(state['inputs'])
            and all(i['execution_epoch'] == state['task']['execution_epoch'] and i['acl_version'] > 0 for i in state['inputs']))
        status, replay = admin('/compilation/wiki-keywords', request)
        check('completedCommandReplaysWithoutAnotherExecution', status == 202
            and replay.get('task_id', replay.get('taskId')) == task_id
            and snapshot(task_id)['task']['attempt'] == 1)
        status, _ = admin('/compilation/wiki-keywords', dict(request, wiki_version=request['wiki_version']+999))
        check('nonexistentWikiRevisionIsRejectedBeforeQueueing', status == 403)
        status = 'PASS_NORMAL_HTTP_DURABLE_COMPILATION_COMMAND'
    except Exception as failure:
        status = 'FAIL_NORMAL_HTTP_DURABLE_COMPILATION_COMMAND'
        observations['failure'] = dict(type=type(failure).__name__, message=str(failure))
    result = dict(status=status, actualOperationTime=datetime.now(timezone.utc).isoformat(),
        jarSha256=json.loads((ROOT/'.tmp/target/process.json').read_text())['jarSha256'],
        checks=checks, observations=observations,
        boundary='Normal local HTTP login/admission and actual PostgreSQL task execution. Only the previously marked fictional Wiki fixture is targeted. No model calls or task/approval status SQL updates. This checks durable keyword projection, not model-generated Wiki quality.')
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    args.output.with_suffix('.sql').write_text('-- Read-only observations; commands were submitted through authenticated HTTP.\n'+'\n'.join(queries)+'\n')
    print(json.dumps(dict(status=status, passed=sum(checks.values()), failed=[n for n, v in checks.items() if not v])))
    if status.startswith('FAIL'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
