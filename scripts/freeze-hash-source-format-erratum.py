"""Disclosed development supplement; never alter the frozen main Hash40 or split."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--main-fixtures', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh freeze directory required')
main_bytes = (a.main_fixtures / 'manifest.json').read_bytes()
main = json.loads(main_bytes)
original = next(c for c in main['cases'] if c['id'] == 'HASHDEV-5-05')
before = (a.main_fixtures / original['before']['path']).read_bytes()
after_original = (a.main_fixtures / original['after']['path']).read_bytes()
if before != after_original or hashlib.sha256(before).hexdigest() != original['before']['sha256']:
    raise SystemExit('Expected original disclosed invalid fixture with identical exact originals')
after = before.replace(b'\n', b'\r\n')
if before == after or b'\r\n' in before:
    raise SystemExit('Expected LF original for independent format supplement')
a.output.mkdir(parents=True)
case = copy.deepcopy(original)
case.update(id='HASHDEV-ERRATA-VERSIONFORMAT-01', split='development', erratumOf=original['id'],
    reason='Original held fixture before/after had identical raw bytes. Existing uq_document_sha correctly rejects manufacturing a second identical original version. This separate disclosed development input changes only original formatting bytes, not normalized body, and cannot count as original held-out success.')
for phase, data in [('before', before), ('after', after)]:
    relative = phase + '.html'
    (a.output / relative).write_bytes(data)
    case[phase] = {'path': relative, 'sha256': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}
manifest = {'version': 'hash-source-format-development-erratum-v1', 'mechanismCaseCount': 1,
    'developmentCases': 1, 'heldOutCases': 0, 'derivedFromDisclosedFailedFixture': True,
    'originalMainManifestUnchanged': hashlib.sha256(main_bytes).hexdigest(), 'cases': [case]}
(a.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps({'status': 'FROZEN_DISCLOSED_DEVELOPMENT_SUPPLEMENT_NOT_MAIN_CASE',
    'manifestSha256': hashlib.sha256((a.output / 'manifest.json').read_bytes()).hexdigest(),
    'caseCount': 1, 'realModelRequests': 0}, ensure_ascii=False))
