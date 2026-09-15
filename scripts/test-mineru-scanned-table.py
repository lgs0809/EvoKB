#!/usr/bin/env python3
"""Actual frozen MinerU digital/scanned continued-table parsing, no embedding/model substitutes."""
import argparse
import datetime
import hashlib
import json
import sqlite3
import subprocess
import time
import urllib.request
import uuid
from html.parser import HTMLParser
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--url', default='http://127.0.0.1:18486')
p.add_argument('--workspace', type=Path, required=True)
p.add_argument('--gold', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh evidence directory required')
a.output.mkdir(parents=True)
gold = json.loads(a.gold.read_text())
original = Path(gold['path'])
assert hashlib.sha256(original.read_bytes()).hexdigest() == gold['sha256']
report = {
    'status': 'RUNNING_ACTUAL_SCANNED_CONTINUATION',
    'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'gold': gold, 'checks': {}, 'samples': [],
    'boundary': 'One actual two-page native MinerU3.4.5 hybrid-engine/medium/auto task: page0 digital, page1 truly raster-only. Native pinned local parsing models only, no Qwen/chat/embedding request. Independent expected row identities, multi-level spans, unit, conditions, digits and page-table positions; no claim for arbitrary scans or all complex tables. Service normal600 deadline and owned6GiB watchdog preserved; actual device/CPU threads are bound to saved launch and native runtime stages. Native MPS may use framework CPU fallback, never claimed as pure GPU execution.',
}


def save():
    (a.output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))


def api(path, data=None, method=None, content_type=None):
    req = urllib.request.Request(a.url + path, data=data, method=method,
                                 headers={'Content-Type': content_type} if content_type else {})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


def canonical(value):
    return ' '.join(value.split()).lower()


class TableRows(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows, self.row, self.cell, self.cells = [], None, None, []

    def handle_starttag(self, tag, attributes):
        if tag == 'tr':
            self.row = []
        elif tag in ('td', 'th'):
            self.cell = ''
            self.cells.append(dict(attributes))

    def handle_data(self, data):
        if self.cell is not None:
            self.cell += data

    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            if self.row is not None:
                self.row.append(canonical(self.cell))
            self.cell = None
        elif tag == 'tr' and self.row is not None:
            self.rows.append(self.row)
            self.row = None


def nested_html(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key in ('html', 'table_body') and isinstance(child, str):
                yield child
            elif isinstance(child, (dict, list)):
                yield from nested_html(child)
    elif isinstance(value, list):
        for child in value:
            yield from nested_html(child)


def iou(left, right):
    overlap = max(0, min(left[2], right[2]) - max(left[0], right[0])) * max(0, min(left[3], right[3]) - max(left[1], right[1]))
    union = (left[2] - left[0]) * (left[3] - left[1]) + (right[2] - right[0]) * (right[3] - right[1]) - overlap
    return overlap / union if union > 0 else 0


task, began = None, time.monotonic()
save()
try:
    with sqlite3.connect(a.workspace / 'tasks.sqlite3') as db:
        active = db.execute("SELECT id,status FROM tasks WHERE status IN ('queued','running')").fetchall()
    assert not active, 'Single parser task only; an existing active task must finish first'
    report['ready'] = api('/ready')
    launch = json.loads((a.workspace / 'launch.json').read_text())
    report['launch'] = {key: launch.get(key) for key in ['pid', 'workspace', 'site', 'device', 'cpuThreads', 'stageSeconds', 'parserSourceSha256', 'parserRuntime', 'hybridManifest', 'hybridManifestSha256']}
    assert report['ready']['parserSourceSha256'] == launch['parserSourceSha256'], 'Actual frozen parser runtime mismatch'
    boundary = 'native-scan-' + uuid.uuid4().hex
    pieces = []
    for name, value in [('backend', 'hybrid-engine'), ('effort', 'medium'), ('parse_method', 'auto'), ('lang_list', 'ch')]:
        pieces.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    pieces.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{original.name}"\r\nContent-Type: application/pdf\r\n\r\n'.encode() + original.read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
    task = api('/tasks', b''.join(pieces), 'POST', 'multipart/form-data; boundary=' + boundary)['task_id']
    report['taskId'] = task
    while time.monotonic() - began < 625:
        state = api('/tasks/' + task)
        report['state'] = state
        stage_path = a.workspace / task / 'runtime-stages.json'
        if stage_path.exists():
            try:
                report['nativeRuntimeStages'] = json.loads(stage_path.read_text())
            except json.JSONDecodeError:
                pass  # A concurrent complete atomic report is read on the next bounded poll.
        worker = state.get('worker_pid')
        if worker:
            result = subprocess.run(['ps', '-axo', 'pid=,pgid=,rss=,%cpu=,time=,stat='], capture_output=True, text=True, timeout=10)
            result.check_returncode()
            owned = [line.strip() for line in result.stdout.splitlines() if len(line.split()) >= 6 and (line.split()[0] == str(worker) or line.split()[1] == str(worker))]
            resident = sum(int(line.split()[2]) for line in owned if not line.split()[-1].startswith('Z'))
            report['samples'].append({'elapsedSeconds': round(time.monotonic() - began, 3), 'actualOwnedProcessGroup': owned, 'ownedRssKiB': resident})
            if resident > 6 * 1024 * 1024:
                report['cancellation'] = api('/tasks/' + task, method='DELETE')
                raise MemoryError('Owned native parser exceeded6GiB; cancelled only this task')
        save()
        if state['status'] in ('completed', 'failed', 'cancelled'):
            break
        time.sleep(3)
    else:
        report['cancellation'] = api('/tasks/' + task, method='DELETE')
        raise TimeoutError('Actual normal600 deadline plus poll allowance exhausted')
    if state['status'] != 'completed':
        raise RuntimeError('Actual parser terminal failure: ' + json.dumps(state.get('error'), ensure_ascii=False))
    actual = api('/tasks/' + task + '/result')
    (a.output / 'native-result.json').write_text(json.dumps(actual, ensure_ascii=False, indent=2))
    file = next(iter(actual['results'].values()))
    pages = file['middle_json']['pdf_info']
    profile = actual['parser_profile']
    checks = report['checks']
    checks['actualPinnedProfileAndOriginalHash'] = actual['version'] == '3.4.5' and actual['effective_backend'] == 'hybrid-engine' and profile['effort'] == 'medium' and profile['parseMethod'] == 'auto' and profile['modelSource'] == 'local' and profile['sourceSha256'] == gold['sha256']
    checks['bothPhysicalPagesHaveNativeContent'] = len(pages) == 2 and {page['page_idx'] for page in pages} == {0, 1} and all(page['para_blocks'] for page in pages)
    text = canonical(json.dumps(file['content_list'], ensure_ascii=False))
    checks['independentInvoiceAcceptanceWarrantyAndHoursSurvive'] = all(canonical(value) in text for value in gold['expectedCriticalText'])
    report['physicalTables'] = []
    for page_index in range(2):
        page = next(page for page in pages if page['page_idx'] == page_index)
        tables = [block for block in page['para_blocks'] if block.get('type') == 'table']
        html = '\n'.join(value for table in tables for value in nested_html(table))
        parser = TableRows()
        parser.feed(html)
        expected_rows = [row for row in gold['expectedRows'] if row['pageIndex'] == page_index]
        observed = []
        for row in expected_rows:
            expected = [canonical(row[key]) for key in ['contract', 'amount', 'dueDays', 'condition']]
            matches = [cells for cells in parser.rows if cells and cells[0] == expected[0]]
            observed.append({'gold': row, 'matchedNativeRows': matches})
            checks[f'page{page_index}:{row["contract"]}:originalRowAndFullCondition'] = matches == [expected]
        checks[f'page{page_index}:repeatedUnitAndAllHeaderFields'] = all(canonical(value) in canonical(html) for value in gold['expectedHeader'])
        checks[f'page{page_index}:realRowspanAndColspanHeader'] = any(cell.get('rowspan') == '2' for cell in parser.cells) and any(cell.get('colspan') == '2' for cell in parser.cells)
        overlap = [iou(table['bbox'], gold['pageTableRegionPoints']) for table in tables if len(table.get('bbox', [])) == 4]
        checks[f'page{page_index}:originalTablePageAndRegionMatch'] = bool(overlap) and max(overlap) >= 0.90
        report['physicalTables'].append({'pageIndex': page_index, 'nativeRows': parser.rows, 'html': html, 'rowIdentityReadback': observed, 'regionIoU': overlap})
    locations = [item for item in file['content_list'] if item.get('bbox')]
    checks['nativeOriginalLocationsValidForBothPages'] = {item.get('page_idx') for item in locations} == {0, 1} and all(len(item['bbox']) == 4 and all(isinstance(value, (int, float)) and 0 <= value <= 1000 for value in item['bbox']) for item in locations)
    with sqlite3.connect(a.workspace / 'tasks.sqlite3') as db:
        db.row_factory = sqlite3.Row
        ledger = dict(db.execute('SELECT id,status,worker_pid,created_at,started_at,finished_at,error FROM tasks WHERE id=?', (task,)).fetchone())
    report['ledger'] = ledger
    checks['normal600ServiceLedgerMatchesActualParse'] = ledger['status'] == 'completed' and ledger['worker_pid'] == state['worker_pid'] and bool(ledger['finished_at'])
    report['status'] = 'PASS_ACTUAL_SCANNED_CONTINUATION' if all(checks.values()) else 'FAIL_ACTUAL_SCANNED_CONTINUATION'
except Exception as error:
    import traceback
    (a.output / 'failure.log').write_text(traceback.format_exc())
    report.update(status='FAIL_ACTUAL_SCANNED_CONTINUATION', failureType=type(error).__name__, failure=str(error))
report.update(finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), elapsedSeconds=round(time.monotonic() - began, 3), probeSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
save()
print(json.dumps({'status': report['status'], 'taskId': task, 'passed': sum(report['checks'].values()), 'failed': [key for key, value in report['checks'].items() if not value], 'elapsedSeconds': report['elapsedSeconds']}, ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
