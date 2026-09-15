#!/usr/bin/env python3
"""Replace only the owned isolated target after public Raw/compile/query tasks are idle."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--verification',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists() or a.output.with_suffix('.log').exists():raise SystemExit('Fresh output required')
a.output.parent.mkdir(parents=True,exist_ok=True)
state=R/'.tmp/target/process.json';before=json.loads(state.read_text())
a.output.with_suffix('.before-process.json').write_bytes(state.read_bytes())
sql="""BEGIN TRANSACTION READ ONLY;
SELECT jsonb_build_object(
 'activeCompilation',(SELECT count(*) FROM public.compile_task WHERE status IN ('RUNNING','PENDING','RETRY_WAIT')),
 'activeIngestion',(SELECT count(*) FROM public.ingestion_file WHERE status IN ('RUNNING','QUEUED')),
 'activeQuery',(SELECT count(*) FROM public.query_session WHERE status='RUNNING' AND deleted_at IS NULL)
);COMMIT;
"""
a.output.with_suffix('.preflight.sql').write_text(sql)
report={'status':'NOT_RUN_DEPLOYMENT_PREFLIGHT','observedAt':datetime.now(timezone.utc).isoformat(),'verificationSha256':hashlib.sha256(a.verification.read_bytes()).hexdigest(),'previousProcess':before,'scope':'Normal owned PID/birth stop and immutable verified artifact start on loopback target only. No business acceptance or task reset.'}
try:
 read=subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-X','-A','-t','-U','evokb_target','-d','evokb_target','-v','ON_ERROR_STOP=1','-f','-'],input=sql,text=True,capture_output=True,timeout=30)
 a.output.with_suffix('.preflight.log').write_text(read.stdout+read.stderr);read.check_returncode()
 counts=json.loads(next(line for line in read.stdout.splitlines() if line.startswith('{')));report['activeTasksBeforeRestart']=counts
 if any(counts.values()):raise ValueError('Current public tasks must finish naturally; no managed process was stopped')
 # Validate artifact/source/config/dependencies before a normal stop, not only after.
 with a.output.with_suffix('.log').open('x') as log:
  subprocess.run(['python3','scripts/deploy-target.py','check','--verification',str(a.verification)],cwd=R,stdout=log,stderr=subprocess.STDOUT,check=True)
  if json.loads(state.read_text())!=before:raise ValueError('Managed process changed since preflight')
  report.update(status='RUNNING_MANAGED_TARGET_REPLACEMENT',deploymentPhase='STOPPING')
  a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
  subprocess.run(['python3','scripts/deploy-target.py','stop'],cwd=R,stdout=log,stderr=subprocess.STDOUT,check=True)
  report.update(managedStopCompleted=True,deploymentPhase='STARTING')
  a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
  result=subprocess.run(['python3','scripts/deploy-target.py','start','--verification',str(a.verification)],cwd=R,stdout=log,stderr=subprocess.STDOUT)
  report['processExitCode']=result.returncode;report['process']=json.loads(state.read_text())
  report['status']='PASS_MANAGED_TARGET_HEALTH_ONLY' if result.returncode==0 else 'FAIL_MANAGED_TARGET_START'
except Exception as error:report.update(status='FAIL_MANAGED_TARGET_START' if report.get('managedStopCompleted') else 'FAIL_MANAGED_TARGET_STOP' if report.get('deploymentPhase')=='STOPPING' else 'NOT_RUN_DEPLOYMENT_PREFLIGHT',failure=type(error).__name__+': '+(str(error.returncode) if isinstance(error,subprocess.CalledProcessError) else str(error)))
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:report.get(k) for k in ['status','activeTasksBeforeRestart','process','failure']},ensure_ascii=False))
raise SystemExit(0 if report['status']=='PASS_MANAGED_TARGET_HEALTH_ONLY' else 1)
