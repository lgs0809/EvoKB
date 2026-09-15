#!/usr/bin/env python3
"""Freeze 30 independent reliability cases:20 development/10 holdout, not assertion counts."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--jar-path', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh manifest directory required; keep prior families/splits unchanged')
a.output.mkdir(parents=True)
groups = [
    ('CLAIM_EXIT', 'C', 4, ['STARTING', 'STORING', 'PARSING', 'STAGED_VECTOR', 'UNCOMMITTED_PUBLICATION']),
    ('RESULT_UNACKED', 'A', 3, ['TERMINAL_BEFORE_ACK'] * 5),
    ('DUPLICATE', 'D', 4, ['XADD_BEFORE_DB_MARK', 'DOUBLE_DELIVERY', 'CONCURRENT_CLAIM', 'TERMINAL_REPLAY', 'TRIPLE_DELIVERY']),
    ('EXPIRED_OWNER', 'E', 3, ['STARTING', 'STORING', 'PARSING', 'EMBEDDING', 'ACTIVATING']),
    ('REDIS_NETWORK', 'N', 3, ['XADD_CONNECTION_CLOSED', 'XREADGROUP_CONNECTION_CLOSED', 'XACK_CONNECTION_CLOSED', 'XPENDING_CONNECTION_CLOSED', 'XADD_RESPONSE_LOST']),
    ('POISON', 'P', 3, ['MISSING_FILE_FIELD', 'BLANK_FILE_ID', 'UNKNOWN_FILE_ID', 'MISSING_ORIGINAL', 'SOURCE_ACL_REVOKED']),
]
cases = []
for group, code, dev_count, faults in groups:
    for index, fault in enumerate(faults):
        ident = f'STREAM-{code}{index + 1:02}'
        body = (f'# Native reliability source {ident}\n'
                f'Family: {ident}. Variant: {fault}.\n'
                f'Synthetic recovery fixture only. Amount: {1000 + len(cases)} CNY.\n'
                'A valid invoice AND written acceptance are both required.\n').encode()
        path = a.output / 'originals' / f'{ident}.md'
        path.parent.mkdir(exist_ok=True)
        path.write_bytes(body)
        expected = 'DELIVERY_ISOLATED' if group == 'POISON' and index < 3 else 'FAILED_ISOLATED' if group == 'POISON' else 'SUCCEEDED_COMPONENT_PUBLICATION'
        cases.append({
            'caseId': ident, 'group': group, 'variant': index + 1,
            'split': 'development' if index < dev_count else 'holdout',
            'sourceFamily': ident, 'originalPath': str(path.resolve()),
            'originalSha256': hashlib.sha256(body).hexdigest(), 'originalBytes': len(body),
            'faultPosition': fault, 'expectedTerminal': expected,
            'expectation': 'Native durable fact before ACK, no duplicate effective projection, original bytes intact. Abrupt process/owner expiry cases require actual fixed600 elapsed; no deadline injection.',
        })
manifest = {
    'status': 'FROZEN_CASES_NOT_EXECUTED',
    'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'artifact': str(a.jar_path.resolve()), 'jarSha256': hashlib.sha256(a.jar_path.read_bytes()).hexdigest(),
    'caseCount': 30, 'developmentCases': 20, 'holdoutCases': 10, 'cases': cases,
    'boundary': 'Six explicit groups each5 independent original/source/version/file cases. Frozen split before actual native execution; no tuning production code from holdout. Case count is separate from assertion count. PG/Redis/MinIO/Milvus component protocol; tiny vectors explicitly synthetic, not an embedding/parser/model or efficiency/Hash result.',
    'builderSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
}
assert len(cases) == 30 and sum(row['split'] == 'development' for row in cases) == 20
(a.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps({key: manifest[key] for key in ['status', 'caseCount', 'developmentCases', 'holdoutCases', 'jarSha256']}, ensure_ascii=False))
