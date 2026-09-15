"""Read-only normal task observation, immutable deployment and owned parser resource guard."""
import argparse,json,subprocess,time,urllib.request,sqlite3,hashlib
from datetime import datetime,timezone
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--file-id',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--timeout',type=int,default=1250);a=p.parse_args();R=Path(__file__).resolve().parents[1]
if a.output.exists():raise SystemExit('Preserve prior attempt; fresh report required')
state=json.loads((R/'.tmp/target/process.json').read_text());report=dict(status='RUNNING_NORMAL_CUA_RAW',startedAt=datetime.now(timezone.utc).isoformat(),deployedArtifact=state,samples=[],checks={},boundary='Normal browser-submitted task; read-only DB/parser ledger/model readiness. Resource guard cancels only this actual parser task if its owned group exceeds 6GiB. No SQL state manufacture.')
q="SELECT row_to_json(f)::text FROM ingestion_file f WHERE file_id='"+a.file_id+"';"
a.output.with_suffix('.readback.sql').write_text(q);start=time.monotonic()
while time.monotonic()-start<a.timeout:
 x=subprocess.run(['docker','exec','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-At','-c',q],capture_output=True,text=True,timeout=10)
 if x.returncode or not x.stdout.strip():report.update(status='FAIL_READBACK',failure=x.stderr);break
 row=json.loads(x.stdout);sample=dict(observedAt=datetime.now(timezone.utc).isoformat(),elapsedSeconds=time.monotonic()-start,file=row)
 task=row.get('parser_task_id')
 if task:
  try:
   with urllib.request.urlopen('http://127.0.0.1:18486/tasks/'+task,timeout=3) as r:parser=json.load(r)
   sample['parser']=parser;pid=parser.get('worker_pid')
   if pid:
    ps=subprocess.check_output(['ps','-axo','pid=,pgid=,rss=,%cpu=,time=,stat='],text=True);owned=[line.strip() for line in ps.splitlines() if len(line.split())>=6 and (line.split()[0]==str(pid) or line.split()[1]==str(pid))];rss=sum(int(line.split()[2]) for line in owned if not line.split()[-1].startswith('Z'));sample.update(ownedParserProcessGroup=owned,ownedGroupRssKiB=rss)
    if rss>6*1024*1024:
     with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18486/tasks/'+task,method='DELETE'),timeout=10) as r:sample['actualCancellation']=json.load(r)
     report.update(status='FAIL_OWNED_MEMORY_GUARD',failure='Actual owned parser exceeded 6GiB');report['samples'].append(sample);break
  except Exception as error:sample['parserObservationFailure']=type(error).__name__
 try:
  with urllib.request.urlopen('http://127.0.0.1:8110/ready',timeout=3) as r:sample['sharedModelReady']=json.load(r)
 except Exception as error:sample['modelObservationFailure']=type(error).__name__
 report['samples'].append(sample);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(elapsed=round(sample['elapsedSeconds'],1),status=row['status'],stage=row.get('execution_stage'),attempt=row['attempt'],parserTask=task,ownedRssMiB=sample.get('ownedGroupRssKiB',0)/1024)),flush=True)
 if row['status'] in ('SUCCEEDED','FAILED'):
  report.update(status='PASS_NORMAL_CUA_RAW_TASK_TERMINAL' if row['status']=='SUCCEEDED' else 'FAIL_NORMAL_CUA_RAW_TASK_TERMINAL',final=row);break
 time.sleep(10)
else:report.update(status='UNFINISHED_OBSERVATION_TIMEOUT',failure='Normal task has not reached terminal; no success claimed or state modified')
report.update(finishedAt=datetime.now(timezone.utc).isoformat(),scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest());a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],output=str(a.output))));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
