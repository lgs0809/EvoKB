"""Freeze Hash40 dev/held mechanisms and a separate 100-document efficiency corpus.

Rates measure changed authored body units (300 independent clauses/table bodies),
not a claimed percentage of Unicode characters, bytes, documents or model tokens.
No model requests, database mutation or results are produced by preparation.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import html
import json
from pathlib import Path

p = argparse.ArgumentParser(); p.add_argument('--output', type=Path, required=True); a = p.parse_args()
if a.output.exists(): raise SystemExit('Fresh freeze directory required')
a.output.mkdir(parents=True)

def document(identity, amount='900.50', warranty='24', row_amount='120.25'):
    return ('<!doctype html>\n<html><head><meta charset="utf-8"></head><body>\n'
        f'<h1>{identity} 专用开发尽调资料</h1>\n'
        '<h2>第一条 付款义务</h2>\n'
        f'<p>采购金额{amount}万元人民币，币种CNY；须取得合法发票且完成书面验收才起算30日付款，未取得发票前付款义务不发生。</p>\n'
        '<h2>第二条 质保义务</h2>\n'
        f'<p>质保期为{warranty}个月；未经书面通知不得转移产权，付款不能替代对缺陷设备的修复义务。</p>\n'
        '<h2>第三条 回款台账</h2>\n'
        '<table><caption>回款台账，单位：万元人民币</caption><thead><tr><th>合同编号</th><th>金额（万元人民币）</th><th>付款期限（日）</th><th>前置条件</th><th>备注</th></tr></thead><tbody>'
        f'<tr><td>{identity}-01</td><td>{row_amount}</td><td>30</td><td>合法发票且书面验收，缺一项不得付款</td><td></td></tr>'
        '</tbody></table>\n</body></html>\n')

def write(relative, text):
    path = a.output / relative; path.parent.mkdir(parents=True, exist_ok=True)
    data = text.encode('utf-8'); path.write_bytes(data)
    return {'path': relative, 'sizeBytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

groups = {
    'NORMALIZATION': [
        ('unchanged', 'No body or actual input change', 0),
        ('crlf', 'Only original LF becomes CRLF; native locations remain original, normalized encoding unchanged', 0),
        ('surrounding_whitespace', 'Only meaningless paragraph edge whitespace changes', 0),
        ('internal_space', 'Internal business text whitespace remains meaningful encoder input', 1),
        ('amount_digit', '900.50 becomes 901.50; amount must not normalize away', 1),
        ('negation', 'Remove 不 from payment obligation; changed meaning must reencode', 1),
        ('unit', '万元人民币 becomes元人民币 in payment clause only', 1),
        ('case_and_punctuation', 'CNY case and semicolon punctuation are retained', 1),
    ],
    'STRUCTURE': [
        ('insert_section', 'Insert a new independent section; old exact encoder inputs reusable', 1),
        ('move_section', 'Move independent sections without changing their heading or content', 0),
        ('parent_title', 'Parent heading participates in every actual encoder input', 3),
        ('child_title', 'Only payment section heading changes', 1),
        ('table_add_column', 'Add a table column without dropping prior cells or units', 1),
        ('table_add_row', 'Append a new original data row', 1),
        ('table_reorder_columns', 'Reorder original columns and values consistently', 1),
        ('table_empty_cell', 'Change an originally empty remark cell', 1),
    ],
    'IDENTITY': [
        ('model_name', 'Explicit synthetic model-name identity invalidation; no real migration', 3),
        ('model_revision', 'Explicit synthetic weight revision invalidation; no new model loaded', 3),
        ('dimensions', 'Explicit synthetic 512-dimension protocol; not Qwen serving verification', 3),
        ('tokenizer_identity', 'Explicit synthetic tokenizer identity wrapper; same native counts, not new tokenizer', 3),
        ('preprocessing', 'Explicit preprocessing contract change', 3),
        ('instruction', 'Explicit task instruction change', 3),
        ('endpoint', 'Explicit endpoint identity change', 3),
        ('processing_domain', 'Identical body in different source processing domain cannot reuse entries', 3),
    ],
    'CACHE_VALIDATION': [
        ('missing_vector_count', 'Backend vector count mismatch fails without cache successes', 0),
        ('wrong_dimensions', 'Backend dimension mismatch fails without cache successes', 0),
        ('null_vector', 'Null vector fails without cache successes', 0),
        ('nan_vector', 'NaN fails without cache successes', 0),
        ('infinite_vector', 'Infinity fails without cache successes', 0),
        ('whole_batch_invalid_tail', 'Valid earlier vectors and invalid last vector must write no partial cache batch', 0),
        ('corrupt_persisted_vector', 'Actual PostgreSQL corrupted cached vector fails closed before encoder request', 0),
        ('concurrent_immutable_winner', 'Concurrent cache writers keep one immutable first vector', 0),
    ],
    'SOURCE_VISIBILITY': [
        ('acl_raise', 'ACL version change hides old native Milvus projection; cache alone grants no access', 0),
        ('acl_lower_republish', 'Lower clearance still requires normal controlled new publication with no reencoding', 0),
        ('deleted_source', 'Deletion hides projection despite cache hit', 0),
        ('inactive_version', 'Inactive source version hides projection despite cache hit', 0),
        ('new_version_same_body', 'Normal new source version publishes fresh source identities while reusing exact inputs', 0),
        ('acl_changed_before_publish', 'Late old ACL claim cannot publish staged rows', 0),
        ('version_changed_before_publish', 'Late old source version claim cannot publish staged rows', 0),
        ('different_domains', 'Two independent permission domains retain source identities and separate cache entries', 3),
    ],
}
cases = []
for group_index, (group, definitions) in enumerate(groups.items()):
    dev_count = 4 if group == 'SOURCE_VISIBILITY' else 5
    for index, (scenario, expectation, miss_count) in enumerate(definitions, 1):
        identity = f'HASHDEV-{group_index + 1}-{index:02d}'
        before = document(identity); after = before
        if scenario == 'crlf': after = before.replace('\n', '\r\n')
        elif scenario == 'surrounding_whitespace': after = before.replace('<p>', '<p>  ').replace('</p>', '  </p>')
        elif scenario == 'internal_space': after = before.replace('30日付款', '30 日付款')
        elif scenario == 'amount_digit': after = before.replace('900.50', '901.50')
        elif scenario == 'negation': after = before.replace('付款义务不发生', '付款义务发生')
        elif scenario == 'unit': after = before.replace('采购金额900.50万元人民币', '采购金额900.50元人民币')
        elif scenario == 'case_and_punctuation': after = before.replace('币种CNY；', '币种Cny：')
        elif scenario == 'insert_section': after = before.replace('<h2>第二条', '<h2>独立新增条款</h2><p>保密义务为12个月，资料只可在授权项目内使用。</p>\n<h2>第二条')
        elif scenario == 'move_section':
            start = before.index('<h2>第一条'); middle = before.index('<h2>第二条'); end = before.index('<h2>第三条')
            after = before[:start] + before[middle:end] + before[start:middle] + before[end:]
        elif scenario == 'parent_title': after = before.replace('专用开发尽调资料', '新版独立资料包')
        elif scenario == 'child_title': after = before.replace('第一条 付款义务', '第一条 更新后的付款义务')
        elif scenario == 'table_add_column': after = before.replace('<th>备注</th>', '<th>备注</th><th>币种</th>').replace('<td></td></tr>', '<td></td><td>CNY</td></tr>')
        elif scenario == 'table_add_row': after = before.replace('</tbody>', f'<tr><td>{identity}-02</td><td>50.25</td><td>60</td><td>无合法发票不得付款</td><td>追加记录</td></tr></tbody>')
        elif scenario == 'table_reorder_columns': after = before.replace('<th>金额（万元人民币）</th><th>付款期限（日）</th>', '<th>付款期限（日）</th><th>金额（万元人民币）</th>').replace('<td>120.25</td><td>30</td>', '<td>30</td><td>120.25</td>')
        elif scenario == 'table_empty_cell': after = before.replace('<td></td>', '<td>空白项补充为须审批</td>')
        split = 'development' if index <= dev_count else 'held_out'
        cases.append({'id': identity, 'group': group, 'scenario': scenario, 'split': split,
            'before': write(f'mechanisms/{identity}-before.html', before),
            'after': write(f'mechanisms/{identity}-after.html', after),
            'authoredExpectation': expectation, 'expectedAfterEncodedInputs': miss_count,
            'expectedBaselineChunks': 3,
            'gold': {'originalAmount': '900.50', 'originalUnit': '万元人民币', 'originalWarranty': '24',
                     'requiredConditions': ['合法发票', '书面验收'], 'negativeCondition': '缺一项不得付款'},
            'vectorScope': 'EXPLICIT_SYNTHETIC_PROTOCOL_VECTORS_NOT_QWEN_INFERENCE'})

body_units = [(n, section) for n in range(1, 101) for section in range(3)]
ranked = sorted(body_units, key=lambda unit: hashlib.sha256(f'fixed-body-edit-order-v1:{unit[0]}:{unit[1]}'.encode()).hexdigest())
documents = []
for n in range(1, 101):
    identity = f'HASHBENCH-DEV-{n:03d}'
    base = document(identity)
    item = {'id': identity, 'developmentFamily': identity, 'baseline': write(f'efficiency/base/{identity}.html', base),
        'bodyUnits': ['payment_clause', 'warranty_clause', 'payment_table'], 'versions': []}
    for rate in [0, 1, 5, 20, 100]:
        selected = set(ranked[:300 * rate // 100]); changed = [section for section in range(3) if (n, section) in selected]
        amount = f'{900 + rate}.50' if 0 in changed else '900.50'
        warranty = str(24 + rate) if 1 in changed else '24'
        table = f'{120 + rate}.25' if 2 in changed else '120.25'
        version = document(identity, amount, warranty, table)
        item['versions'].append({'bodyUnitChangeRatePercent': rate,
            'changedBodyUnits': changed, 'original': write(f'efficiency/change-{rate:03d}/{identity}.html', version),
            'gold': {'paymentAmount': amount, 'warrantyMonths': warranty, 'tableAmount': table,
                'unit': '万元人民币', 'paymentPrerequisites': ['合法发票', '书面验收'], 'missingInvoiceNegation': '付款义务不发生'}})
    documents.append(item)

manifest = {'version': 'hash-development-v1', 'createdAt': datetime.now(timezone.utc).isoformat(),
    'mechanismCaseCount': 40, 'developmentCases': 24, 'heldOutCases': 16,
    'mechanismBoundary': 'Actual immutable production cache/parser/chunk/PG/Milvus protocol mechanisms with explicitly synthetic vectors. No model-quality, actual model migration, or Hash efficiency claim.',
    'cases': cases, 'efficiency': {'developmentDocuments': 100, 'authoredBodyUnitCount': 300,
        'rateDefinition': 'Changed independently authored clause/table body units divided by the fixed 300 units. This is not character, byte, tokenizer token, or document percentage.',
        'versions': [{'ratePercent': rate, 'changedBodyUnits': 300 * rate // 100} for rate in [0, 1, 5, 20, 100]],
        'actualInferenceStatus': 'NOT_RUN', 'fullRebuildAndHashActualInputsMustBeObserved': True,
        'documents': documents}}
(a.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps({'mechanisms': len(cases), 'development': sum(c['split'] == 'development' for c in cases),
    'heldOut': sum(c['split'] == 'held_out' for c in cases), 'independentEfficiencyDocs': len(documents),
    'manifestSha256': hashlib.sha256((a.output / 'manifest.json').read_bytes()).hexdigest()}))
