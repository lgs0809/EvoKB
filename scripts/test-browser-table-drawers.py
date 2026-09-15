#!/usr/bin/env python3
"""Verify retained actual CUA table DOM and copy bytes against native current PG."""
import argparse, datetime, hashlib, json, re, subprocess
from pathlib import Path

R = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--evidence-dir', type=Path, required=True)
p.add_argument('--docx-version-id', required=True)
p.add_argument('--web-release', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists(): raise SystemExit('Fresh report required; preserve prior failures')
assert re.fullmatch('[a-f0-9-]{36}', a.docx_version_id)
checks = {}
report = {'status':'RUNNING_ACTUAL_TABLE_DRAWER_AND_ORIGINAL', 'deployedArtifact':json.loads((R/'.tmp/target/process.json').read_text()), 'webRelease':str(a.web_release.resolve()), 'boundary':'Actual normal Chrome evidence pages and native current PG readback. Confirms these real fixture rows/conditions/spans/copy bytes and stored PDF highlight, not new PDF inference or generalized malicious-HTML testing. Prior literal-table FAIL screenshot retained.'}
try:
    q = "SELECT json_build_object('raw',b.raw_content,'blockId',b.block_id,'evidenceId',e.evidence_id,'active',e.active,'versionId',b.version_id)::text FROM raw_block b JOIN evidence e ON e.block_id=b.block_id WHERE b.version_id='"+a.docx_version_id+"' AND lower(b.block_type)='table' AND e.active=TRUE;"
    a.output.with_suffix('.readback.sql').write_text(q)
    r = subprocess.run(['docker','exec','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-At','-c',q], capture_output=True, text=True, timeout=10)
    r.check_returncode()
    rows = r.stdout.strip().splitlines()
    assert len(rows)==1 and rows[0], 'Expected one actual current table, got '+str(len(rows))
    db=json.loads(rows[0]); copied=(a.evidence_dir/'docx-copy-original.txt').read_text()
    docx=json.loads((a.evidence_dir/'docx-evidence-drawer-table-fixed.json').read_text())
    pdf=json.loads((a.evidence_dir/'pdf-evidence-drawer-table-fixed.json').read_text())
    checks['docxMergedHeaderKeepsOriginalSpan2']=docx['rows'][0][0]['rowspan']=='2' and docx['rows'][0][1]['colspan']=='2'
    checks['docxAllOriginalTableRowsAndAmountsRemain']=len(docx['rows'])==5 and all(t in docx['text'] for t in ['HT-900','900','HT-901','0','HT-902','120.50'])
    checks['docxConditionsAndTableCaptionRemain']=all(t in docx['text'] for t in ['发票且验收后60日','暂缓付款','质保24个月','缺少合法发票时不得付款'])
    checks['pdfActualRowsAndAmountsRemain']=len(pdf['rows'])==3 and all(t in pdf['text'] for t in ['900.00','120.50','24 months','12 months','60','30'])
    checks['pdfNoInvoiceNoPaymentCaptionRemains']='No invoice: no payment obligation.' in pdf['text']
    checks['bothDrawersUseFormattingOnlyForTheseActualFixtures']=docx['unsafeElements']==0 and pdf['unsafeElements']==0
    checks['copyActionPreservesExactPersistedOriginal']=copied==db['raw'] and db['active']
    checks['actualPdfFileHighlightScreenshotSaved']=(a.evidence_dir/'pdf-source-highlight-current.jpg').is_file()
    report.update(clipboardSha256=hashlib.sha256(copied.encode()).hexdigest(),actualEvidenceIdentity={k:v for k,v in db.items() if k!='raw'})
    report['status']='PASS_ACTUAL_TABLE_DRAWER_AND_ORIGINAL' if all(checks.values()) else 'FAIL_ACTUAL_TABLE_DRAWER_AND_ORIGINAL'
except Exception as error:
    report.update(status='FAIL_ACTUAL_TABLE_DRAWER_AND_ORIGINAL',failure=type(error).__name__+': '+str(error))
report.update(checks=checks,actualAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({'status':report['status'],'checks':checks,'failure':report.get('failure')},ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
