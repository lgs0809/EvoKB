"""Freeze development originals and authored business Gold before native chunk execution.

This is a source-native HTML/CSV chunk experiment, not forty MinerU/PDF parses.
All facts use a dedicated CHDEV namespace and do not reuse formal evaluation sources.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import html
import io
import json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh freeze directory required; never replace an existing Gold')
a.output.mkdir(parents=True)
cases = []
families = [('CONTRACT', '采购合同'), ('ANNEX', '补充协议'), ('ACCEPTANCE', '验收与说明'), ('FINANCE', '回款财务表')]
for family, label in families:
    for n in range(1, 11):
        case_id = f'CHDEV-{family}-{n:02d}'
        days, money = 15 + n * 3, f'{700 + n * 17}.50'
        title = f'{case_id} {label}'
        sentences = []
        for k in range(1, 25):
            if family == 'CONTRACT':
                sentence = (f'条目{n:02d}-{k:02d}约定第{k}批设备金额为{money}万元人民币，付款期限为{days}个自然日，'
                    '起算须同时满足已取得合法发票和完成书面验收；未取得发票前付款义务不发生，口头确认不能代替书面验收。')
            elif family == 'ANNEX':
                sentence = (f'条目{n:02d}-{k:02d}仅将第{k}批的原付款期限30日调整为{days}个自然日，'
                    f'原金额{money}万元人民币和24个月质保期保持不变；合法发票且书面验收两项前提均继续有效，未验收不能请求付款。')
            elif family == 'ACCEPTANCE':
                sentence = (f'条目{n:02d}-{k:02d}记录第{k}批金额{money}万元人民币，检查结论为附条件接受，'
                    '须在72小时内提交整改证据并取得书面复验；暂收设备不代表最终验收通过，未提交整改证据不得起算付款期限。')
            else:
                sentence = (f'条目{n:02d}-{k:02d}核对第{k}批应收金额{money}万元人民币，'
                    f'账龄阈值为{days}个自然日；无合法发票的记录不得作为可支付债务，单位为万元而不是元，逾期说明不能替代实际付款凭据。')
            sentences.append(sentence)
        headers = ['合同编号', '金额（万元人民币）', '付款计划', '付款期限（日）', '前置条件']
        rows = []
        for k in range(1, 29):
            condition = ('未取得合法发票前不得付款，完成书面验收后方可起算。' if k % 2 else
                         '须同时收到合法发票和书面验收单，缺任一材料付款义务不发生。')
            rows.append([f'{case_id}-ROW-{k:02d}', f'{100 + n * 7 + k}.25', str(days + k), condition])
        expected_blocks = []
        if family == 'FINANCE' and n > 5:
            extension = '.csv'
            s = io.StringIO(newline='')
            writer = csv.writer(s, lineterminator='\r\n')
            offsets = []
            for record, cells in enumerate([['合同编号', '金额（万元人民币）', '付款期限（日）', '前置条件'], *rows], 1):
                offsets.append({'record': record, 'characterOffset': s.tell(), 'cells': cells})
                writer.writerow(cells)
            original = s.getvalue()
            expected_blocks.append({'type': 'table', 'csvRecords': offsets})
            sentences = []
            table_headers = ['合同编号', '金额（万元人民币）', '付款期限（日）', '前置条件']
            header_rows = 1
        else:
            extension = '.html'
            lines = ['<!doctype html>', '<html><head><meta charset="utf-8"></head><body>']
            def element(tag, identity, body):
                expected_blocks.append({'type': 'table' if tag == 'table' else 'text',
                    'selectorId': identity, 'line': len(lines) + 1, 'column': 1,
                    **({'exactText': body} if tag != 'table' else {})})
                lines.append(f'<{tag} id="{identity}">{html.escape(body)}</{tag}>')
            element('h1', 'document-title', title)
            element('h2', 'terms-title', '付款与否定条件')
            # One long continuous paragraph plus adjacent paragraphs make target/overlap material.
            element('p', 'long-terms', ''.join(sentences[:18]))
            for k in range(18, 24):
                element('p', f'clause-{k + 1}', sentences[k])
            element('h2', 'ledger-title', '分批回款台账')
            table_line = len(lines) + 1
            table = '<table id="payment-ledger"><caption>分批回款台账；金额单位：万元人民币</caption><thead>'
            table += '<tr><th rowspan="2">合同编号</th><th rowspan="2">金额（万元人民币）</th><th colspan="2">付款计划</th></tr>'
            table += '<tr><th>付款期限（日）</th><th>前置条件</th></tr></thead><tbody>'
            for cells in rows:
                table += '<tr>' + ''.join('<td>' + html.escape(cell) + '</td>' for cell in cells) + '</tr>'
            table += '</tbody></table>'
            expected_blocks.append({'type': 'table', 'selectorId': 'payment-ledger', 'line': table_line, 'column': 1})
            lines.extend([table, '</body></html>'])
            original = '\n'.join(lines) + '\n'
            table_headers = headers
            header_rows = 2
        filename = case_id + extension
        data = original.encode('utf-8')
        (a.output / filename).write_bytes(data)
        questions = [
            {'id': case_id + '-Q1', 'question': f'{case_id}付款起算的材料要求是什么？缺材料能否付款？',
             'necessaryConditions': ['合法发票', '书面验收'], 'negativeMeaningRequired': True},
            {'id': case_id + '-Q2', 'question': f'{case_id}台账首笔金额、单位和付款期限是多少？',
             'requiredCells': rows[0]},
            {'id': case_id + '-Q3', 'question': f'{case_id}台账最后一笔在缺发票时是否可付款？',
             'requiredCells': rows[-1]},
        ]
        cases.append({'id': case_id, 'family': family, 'developmentFamily': case_id,
            'filename': filename, 'sizeBytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
            'expectedParsedBlocks': expected_blocks, 'authoredSentences': sentences,
            'table': {'headerRows': header_rows, 'headers': table_headers,
                'caption': None if extension == '.csv' else '分批回款台账；金额单位：万元人民币',
                'rows': [{'sourceRow': k + header_rows, 'cells': cells} for k, cells in enumerate(rows)]},
            'fixedQuestions': questions})
manifest = {'version': 'chunk-development-40-v1', 'createdAt': datetime.now(timezone.utc).isoformat(),
    'caseCount': 40, 'documentConfigurationExecutions': 360, 'developmentOnly': True,
    'profiles': [{'target': target, 'hardLimit': 900, 'overlap': overlap, 'tail': 150}
                 for target in [400, 600, 800] for overlap in [0, 80, 120]],
    'boundary': '40 authored source-native HTML/CSV development originals. Offline structure/location/token checks do not constitute MinerU/PDF parsing, model QA quality, retrieval recall, or QA latency comparisons.',
    'cases': cases}
(a.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps({'cases': len(cases), 'documentsByFamily': {f: sum(c['family'] == f for c in cases) for f, _ in families},
    'totalBytes': sum(c['sizeBytes'] for c in cases), 'manifestSha256': hashlib.sha256((a.output / 'manifest.json').read_bytes()).hexdigest()}))
