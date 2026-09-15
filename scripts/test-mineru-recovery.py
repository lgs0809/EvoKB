"""Real owned-process restart and accelerated deadline, using separate local parser workspaces."""
import argparse,hashlib,json,os,signal,sqlite3,subprocess,sys,time,urllib.error,urllib.request,uuid
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--fixture',type=Path,required=True);p.add_argument('--slow',type=Path,required=True);p.add_argument('--restart-port',type=int,default=18487);p.add_argument('--deadline-port',type=int,default=18488);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence output required')
a.output.mkdir(parents=True);checks={};events=[];pids=[]
def api(port,path,method='GET',payload=None,ctype=None):
 with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}'+path,method=method,data=payload,headers={'Content-Type':ctype} if ctype else {}),timeout=15) as response:result=json.load(response)
 events.append(dict(port=port,path=path,method=method,response=result));return result
def submit(port,path):
 boundary='evokb-'+uuid.uuid4().hex
 body=f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{path.name}"\r\n\r\n'.encode()+path.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
 return api(port,'/tasks','POST',body,'multipart/form-data; boundary='+boundary)['task_id']
def wait(port,task,wanted=None):
 end=time.monotonic()+25
 while time.monotonic()<end:
  result=api(port,'/tasks/'+task)
  if result['status']==wanted or result['status'] in ('completed','failed','cancelled'):return result
  time.sleep(.03)
 raise RuntimeError('Task acceptance deadline exceeded')
def alive(pid):
 try:os.kill(pid,0);return True
 except ProcessLookupError:return False
def launch(port,workspace,seconds):
 result=subprocess.run([sys.executable,str(R/'scripts/start-mineru-service.py'),'--workspace',str(workspace),'--port',str(port),'--stage-seconds',str(seconds)],capture_output=True,text=True,timeout=20)
 events.append(dict(launch=result.stdout,error=result.stderr,exitCode=result.returncode))
 if result.returncode:raise RuntimeError('Owned parser did not launch')
 receipt=json.loads((workspace/'launch.json').read_text());pids.append(receipt['pid']);return receipt
def stop(pid):
 os.kill(pid,signal.SIGTERM);end=time.monotonic()+10
 while alive(pid) and time.monotonic()<end:time.sleep(.05)
 if alive(pid):raise RuntimeError('Owned parser service did not stop gracefully')
report=dict(createdAt=datetime.now(timezone.utc).isoformat(),status='FAIL')
try:
 workspace=a.output/'restart-workspace';receipt=launch(a.restart_port,workspace,600)
 first=submit(a.restart_port,a.slow);running=wait(a.restart_port,first,'running');second=submit(a.restart_port,a.fixture)
 stop(receipt['pid']);checks['gracefulShutdownReleasedOwnedRunningWorker']=not alive(running['worker_pid'])
 restarted=launch(a.restart_port,workspace,600);after=api(a.restart_port,'/tasks/'+first);resumed=wait(a.restart_port,second)
 checks['interruptedTaskRetainedAsFailure']=after['status']=='failed' and after['error']['code']=='PARSER_PROCESS_INTERRUPTED'
 checks['queuedTaskRecoveredExactlyOnce']=resumed['status']=='completed' and (workspace/second/'original').read_bytes()==a.fixture.read_bytes()
 with sqlite3.connect(workspace/'tasks.sqlite3') as db:checks['restartLedgerHasOneRowPerOriginal']=db.execute('SELECT count(*) FROM tasks').fetchone()[0]==2
 stop(restarted['pid'])
 # With memory pressure removed, the former 1500-paragraph input legitimately finished in 0.64s.
 # Use a retained real native workload large enough to exceed the shortened 1s deadline; no sleeps.
 from docx import Document
 deadlineoriginal=a.output/'deadline-original.docx';nativework=Document()
 for i in range(20000):nativework.add_paragraph(f'条款{i}：未取得合法发票不付款；完成验收后60日，金额900万元，质保24个月。')
 nativework.save(deadlineoriginal)
 report['deadlineInput']={'sha256':hashlib.sha256(deadlineoriginal.read_bytes()).hexdigest(),'paragraphs':20000,'path':str(deadlineoriginal)}
 deadlineworkspace=a.output/'deadline-workspace';short=launch(a.deadline_port,deadlineworkspace,1);task=submit(a.deadline_port,deadlineoriginal);started=wait(a.deadline_port,task,'running');expired=wait(a.deadline_port,task)
 checks['acceleratedNativeDeadlineTerminatesActualProcess']=started['status']=='running' and expired['status']=='failed' and expired['error']['code']=='PARSER_STAGE_TIMEOUT' and not alive(started['worker_pid'])
 checks['productionAndAcceleratedLimitsDistinguished']=receipt['stageSeconds']==600 and short['stageSeconds']==1
 stop(short['pid']);report['status']='PASS_ACTUAL_MINERU_RECOVERY' if all(checks.values()) else 'FAIL_ACTUAL_MINERU_RECOVERY'
except Exception as error:
 import traceback;(a.output/'failure.log').write_text(traceback.format_exc());report['error']=str(error)
finally:
 for pid in pids:
  if alive(pid):
   try:stop(pid)
   except Exception as error:events.append(dict(cleanupUnconfirmed=str(error),pid=pid))
report.update(checks=checks,events=events,sourceHashes={str(path.relative_to(R)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [Path(__file__),R/'scripts/mineru-service/app.py',R/'scripts/start-mineru-service.py']},boundary='Actual native Office subprocess stopped and restarted. Deadline mechanism executes at an explicitly shortened 1 second; production service remains 600 seconds. No actual 600-second parser inference wait or PDF hybrid success claim.')
(a.output/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=checks),ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
