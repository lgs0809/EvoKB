#!/usr/bin/env python3
"""Read original fixed-case PG/Redis facts after an unrelated dependency outage; never publish."""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--prior-report',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence required')
prior=json.loads(a.prior_report.read_text());schema=prior['postgresSchema']
if not re.fullmatch(r'raw_stream30_[0-9a-f]{12}',schema):raise SystemExit('Only the original isolated fixed-case schema is allowed')
cases=prior['cases']
if len(cases)!=30 or len({c['caseId'] for c in cases})!=30:raise SystemExit('All original frozen30 case identities required')
if not all(re.fullmatch('[0-9a-f-]{36}',c['fileId']) for c in cases):raise SystemExit('Canonical original file IDs required')
report=dict(status='RUNNING_RAW_STREAM30_RECOVERY_READBACK',observedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),originalExecutionJarSha256=prior['jarSha256'],priorStatus=prior['status'],priorReportSha256=hashlib.sha256(a.prior_report.read_bytes()).hexdigest(),postgresSchema=schema,caseCount=30,checks={},observations=[],boundary='Pure SELECT and native Redis XRANGE/XPENDING only, against original retained schema/streams. Captures conservation after a non-planned PG/Milvus outage; does not claim any unfinished case passed, change owner/deadline/status, ACK, publish, restart or invoke models.')

def run(parts):
 r=subprocess.run(parts,capture_output=True,text=True,timeout=20);r.check_returncode();return r.stdout.strip()

try:
 ids=','.join("'"+c['fileId']+"'" for c in cases)
 sql=f"""SET search_path TO {schema};
SELECT json_build_object('fileId',f.file_id,'status',f.status,'attempt',f.attempt,'epoch',f.execution_epoch,'owner',f.owner_id,'stage',f.execution_stage,'started',f.stage_started_at,'deadline',f.stage_deadline,'fixed600',f.stage_deadline=f.stage_started_at+interval '600 seconds','expired',f.stage_deadline<=clock_timestamp(),'databaseNow',clock_timestamp(),'versionId',v.version_id,'originalSha256',v.sha256,'objectKey',v.original_object_key,'publicationCount',(SELECT count(*) FROM raw_publication p WHERE p.version_id=v.version_id),'publicationMembers',(SELECT coalesce(json_agg(m.physical_id),'[]') FROM raw_publication_member m WHERE m.version_id=v.version_id),'failures',(SELECT count(*) FROM raw_ingestion_failure x WHERE x.file_id=f.file_id),'outboxMessageId',o.message_id)::text
FROM ingestion_file f JOIN document_version v ON v.version_id=f.version_id
LEFT JOIN raw_ingestion_outbox o ON o.file_id=f.file_id WHERE f.file_id IN ({ids});
"""
 # Some poison deliveries intentionally never bind a file to its version; use fixture identity.
 pairs=' UNION ALL '.join("SELECT '"+c['fileId']+"'::text file_id,'"+c['versionId']+"'::text version_id" for c in cases)
 sql=sql.replace('FROM ingestion_file f JOIN document_version v ON v.version_id=f.version_id','FROM ingestion_file f JOIN ('+pairs+') fixture ON fixture.file_id=f.file_id JOIN document_version v ON v.version_id=fixture.version_id')
 a.output.parent.mkdir(parents=True,exist_ok=True);a.output.with_suffix('.readback.sql').write_text(sql)
 rows={r['fileId']:r for r in (json.loads(line) for line in run(['docker','exec','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-At','-q','-c',sql]).splitlines() if line.startswith('{'))}
 report['checks']['all30OriginalFileVersionFactsRetained']=set(rows)=={c['fileId'] for c in cases}
 for c in cases:
  row=rows[c['fileId']];base=['docker','exec','evokb-target-redis-1','redis-cli','--json']
  body=json.loads(run(base+['XRANGE',c['stream'],'-','+']))
  pel=json.loads(run(base+['XPENDING',c['stream'],'fixed-thirty','-','+','100']))
  report['checks'][c['caseId']+':originalSourceHashRetained']=row['originalSha256']==c['originalSha256'] and row['objectKey']==c['objectKey']
  waiting=c['status']=='WAITING_ACTUAL600'
  if waiting:
   owner=c['actualOwner']
   report['checks'][c['caseId']+':originalExpiredOwnerRetainedUnrenewed']=row['status']=='RUNNING' and row['attempt']==1 and row['epoch']==1 and row['owner']==owner['owner_id'] and row['fixed600'] is True and row['expired'] is True
   report['checks'][c['caseId']+':unfinishedHasNoPublicationAndKeepsPel']=row['publicationCount']==0 and len(pel)==1
  elif c['expectedTerminal']=='SUCCEEDED_COMPONENT_PUBLICATION':
   report['checks'][c['caseId']+':committedTerminalAndPointerRetained']=row['status']=='SUCCEEDED' and row['attempt']==1 and row['publicationCount']==1 and len(row['publicationMembers'])==1 and pel==[]
  elif c['expectedTerminal']=='FAILED_ISOLATED':
   report['checks'][c['caseId']+':isolationFactRetainedNoPublication']=row['status']=='FAILED' and row['attempt']==1 and row['publicationCount']==0 and row['failures']==1 and pel==[]
  else:
   report['checks'][c['caseId']+':poisonDidNotConsumeTaskAttempt']=row['attempt']==0 and row['publicationCount']==0 and pel==[]
  report['checks'][c['caseId']+':streamBodiesStillRetained']=len(body)>=1
  report['observations'].append(dict(caseId=c['caseId'],split=c['split'],priorCaseStatus=c['status'],currentFacts=row,retainedStreamBodies=body,exactPendingEntries=pel,caseConclusion='UNFINISHED_AFTER_UNPLANNED_OUTAGE' if waiting else 'TERMINAL_FACT_RETAINED_NOT_FULL_CASE_READBACK'))
 report['unfinishedCaseCount']=sum(c['status']=='WAITING_ACTUAL600' for c in cases)
 report['terminalCaseFactCount']=30-report['unfinishedCaseCount']
 report['status']='PASS_RAW_STREAM30_RECOVERY_FACT_CONSERVATION' if all(report['checks'].values()) else 'FAIL_RAW_STREAM30_RECOVERY_FACT_CONSERVATION'
except Exception as error:
 report.update(status='FAIL_RAW_STREAM30_RECOVERY_FACT_CONSERVATION',failure=type(error).__name__+': '+str(error))
report.update(scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:report.get(k) for k in ['status','caseCount','unfinishedCaseCount','terminalCaseFactCount','failure']}));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
