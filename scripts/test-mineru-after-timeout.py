"""Read back a real parser timeout, then submit a retained synthetic DOCX through normal HTTP."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', default='http://127.0.0.1:18486')
parser.add_argument('--workspace', type=Path, required=True)
parser.add_argument('--fixture', type=Path, required=True)
parser.add_argument('--failed-task', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if args.output.exists():
    raise SystemExit('Use a fresh evidence directory; preserve earlier attempts')
args.output.mkdir(parents=True)
events = []


def api(path, *, data=None, content_type=None):
    request = urllib.request.Request(args.url + path, data=data,
                                    headers={'Content-Type': content_type} if content_type else {})
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.load(response)
    events.append(dict(path=path, response=result))
    return result


def alive(pid):
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


report = dict(actualOperationTime=datetime.now(timezone.utc).isoformat(), status='FAIL', checks={})
try:
    original = args.fixture.read_bytes()
    ready = api('/ready')
    failed = api('/tasks/' + args.failed_task)
    checks = report['checks']
    checks['actual600SecondTimeoutRetainedAndOwnedWorkerGone'] = (
        ready['stageSeconds'] == 600 and ready['workerPid'] is None
        and failed['status'] == 'failed' and failed['error']['code'] == 'PARSER_STAGE_TIMEOUT'
        and not alive(failed['worker_pid']))
    if not checks['actual600SecondTimeoutRetainedAndOwnedWorkerGone']:
        raise RuntimeError('Expected natural timeout/resource release was not observed')
    boundary = 'evokb-' + uuid.uuid4().hex
    payload = []
    for key, value in [('backend', 'hybrid-engine'), ('effort', 'medium'),
                       ('parse_method', 'auto'), ('lang_list', 'ch')]:
        payload.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    payload.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="invoice-acceptance.docx"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
                   + original + b'\r\n')
    payload.append(f'--{boundary}--\r\n'.encode())
    task = api('/tasks', data=b''.join(payload),
               content_type='multipart/form-data; boundary=' + boundary)['task_id']
    report['taskId'] = task
    started = time.monotonic()
    while True:
        state = api('/tasks/' + task)
        if state['status'] in ('completed', 'failed', 'cancelled'):
            break
        if time.monotonic() - started > 45:
            raise RuntimeError('Recovery observation window ended; task retained without cancellation')
        time.sleep(.2)
    result = api('/tasks/' + task + '/result')
    (args.output / 'actual-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    text = json.dumps(result, ensure_ascii=False)
    checks['newNativeOfficeActorActuallyCompletes'] = (
        state['status'] == 'completed' and state['worker_pid'] != failed['worker_pid']
        and alive(state['worker_pid']) and result['effective_backend'] == 'office'
        and result['parser_profile']['modelRevision'] == 'native-office-mineru-3.4.5')
    checks['retainedSyntheticOfficeFactsAndTableSpansSurvive'] = all(
        value in text for value in ['900万元', '24个月', '60日', 'HT-902', '120.50', 'rowspan', 'colspan'])
    after = api('/ready')
    checks['original600SecondProfileAndBundleUnchanged'] = all(
        after.get(key) == ready.get(key) for key in
        ['stageSeconds', 'cpuThreads', 'parserRuntimeBundleSha256', 'parserSourceSha256', 'hybridManifest'])
    with sqlite3.connect('file:' + str((args.workspace / 'tasks.sqlite3').resolve()) + '?mode=ro', uri=True) as db:
        rows = dict(db.execute('SELECT id,status FROM tasks WHERE id IN (?,?)', (task, args.failed_task)))
    checks['sqliteLedgerAndOriginalBytesAgreeWithHttp'] = (
        rows == {task: 'completed', args.failed_task: 'failed'}
        and (args.workspace / task / 'original').read_bytes() == original)
    report.update(status='PASS_ACTUAL_NATIVE_OFFICE_RECOVERY_AFTER_600S_TIMEOUT'
                  if all(checks.values()) else 'FAIL_ACTUAL_NATIVE_OFFICE_RECOVERY_AFTER_600S_TIMEOUT',
                  elapsedSeconds=time.monotonic() - started,
                  inputSha256=hashlib.sha256(original).hexdigest())
except Exception as failure:
    report.update(failureType=type(failure).__name__, failure=str(failure))
report.update(events=events, scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              boundary='Real native Office HTTP/task-ledger recovery after an actual 600-second PDF timeout. '
              'No parser configuration change, no Raw publication, no PDF quality success and no embedding/chat inference.')
(args.output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({key: report[key] for key in ['status', 'checks']}, ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
