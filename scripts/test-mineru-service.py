"""Actual HTTP/native Office + task ledger + process cancellation; retains each request/result."""
import argparse,concurrent.futures,hashlib,json,os,signal,sqlite3,time,urllib.error,urllib.request,uuid
from datetime import datetime,timezone
from pathlib import Path
from docx import Document
from reportlab.pdfgen import canvas

R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18486');p.add_argument('--workspace',type=Path,default=R/'.tmp/mineru-service');p.add_argument('--fixture',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence output required')
a.output.mkdir(parents=True)
requests=[]
def api(path,method='GET',payload=None,contenttype=None):
 request=urllib.request.Request(a.url+path,data=payload,method=method,headers={'Content-Type':contenttype} if contenttype else {})
 with urllib.request.urlopen(request,timeout=15) as response:
  data=response.read();value=json.loads(data) if response.headers.get_content_type()=='application/json' else data
 requests.append(dict(method=method,path=path,status=response.status,response=value if not isinstance(value,bytes) else dict(size=len(value),sha256=hashlib.sha256(value).hexdigest())))
 return value

def submit(filename,data):
 boundary='evokb-'+uuid.uuid4().hex
 parts=[]
 for key,value in [('backend','hybrid-engine'),('effort','medium'),('parse_method','auto'),('lang_list','ch')]:parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
 parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+data+b'\r\n')
 parts.append(f'--{boundary}--\r\n'.encode());return api('/tasks','POST',b''.join(parts),'multipart/form-data; boundary='+boundary)['task_id']
def wait(task,wanted=None,timeout=20):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  state=api('/tasks/'+task)
  if state['status'] in ('completed','failed','cancelled') or state['status']==wanted:return state
  time.sleep(.05)
 raise RuntimeError('Actual parser task did not finish within test deadline')
def alive(pid):
 try:os.kill(pid,0);return True
 except ProcessLookupError:return False

report=dict(createdAt=datetime.now(timezone.utc).isoformat(),status='FAIL',checks={},tasks=[])
try:
 ready=api('/ready');report['ready']=ready
 actual=submit('invoice-acceptance.docx',a.fixture.read_bytes());report['tasks'].append(actual);state=wait(actual);result=api('/tasks/'+actual+'/result');file=next(iter(result['results'].values()));text=json.dumps(file['content_list'],ensure_ascii=False)
 report['checks']['actualNativeOfficeAndFrozenProfile']=state['status']=='completed' and result['version']=='3.4.5' and result['effective_backend']=='office' and result['parser_profile']['modelRevision']=='native-office-mineru-3.4.5' and all(x in text for x in ['900万元','24个月','60日','HT-902','120.50','rowspan','colspan'])
 (a.output/'actual-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
 imageok=bool(file['images'])
 for name,entry in file['images'].items():
  image=api('/tasks/'+actual+'/images/'+entry['path']);imageok=imageok and hashlib.sha256(image).hexdigest()==entry['sha256'] and len(image)==entry['size'];(a.output/Path(name).name).write_bytes(image)
 report['checks']['actualImageBytesMatchResultHash']=imageok
 # Different tasks with the same original filename remain isolated; the worker is retained.
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
  tasks=list(executor.map(lambda _:submit('invoice-acceptance.docx',a.fixture.read_bytes()),range(3)))
 report['tasks'].extend(tasks);states=[wait(task) for task in tasks]
 report['checks']['concurrentOriginalsIsolatedAndResidentWorker']=len(set(tasks))==3 and all(s['status']=='completed' for s in states) and len(set(s['worker_pid'] for s in states+[state]))==1 and all((a.workspace/task/'original').read_bytes()==a.fixture.read_bytes() for task in tasks)
 damaged=submit('damaged.docx',b'not-a-zip');report['tasks'].append(damaged);damagedstate=wait(damaged);report['checks']['damagedOriginalNeverPublished']=damagedstate['status']=='failed' and damagedstate['error']['code']=='INPUT_ERROR'
 # Native PDF input validation is separate from missing hybrid dependencies.
 pdf=a.output/'valid-input.pdf';c=canvas.Canvas(str(pdf));c.drawString(30,750,'HT-900: USD 900; invoice and acceptance required.');c.save();pdfjob=submit('valid-input.pdf',pdf.read_bytes());report['tasks'].append(pdfjob);pdfstate=wait(pdfjob)
 report['checks']['missingHybridIsExplicitlyBlocked']=pdfstate['status']=='failed' and pdfstate['error']['code']=='DEPENDENCY_NOT_AVAILABLE'
 # Create real native workload, cancel after the service has assigned its own worker PID.
 slow=a.output/'cancellation-original.docx';doc=Document()
 for i in range(1500):doc.add_paragraph(f'条款{i}：未取得合法发票不付款；验收后60日，金额900万元，质保24个月。')
 doc.save(slow);task=submit('cancellation-original.docx',slow.read_bytes());report['tasks'].append(task);running=wait(task,'running');cancel=api('/tasks/'+task,'DELETE');after=wait(task)
 report['checks']['runningNativeProcessActuallyCancelled']=running['status']=='running' and cancel['resource_release_confirmed'] is True and after['status']=='cancelled' and not alive(running['worker_pid'])
 recovered=submit('invoice-acceptance.docx',a.fixture.read_bytes());report['tasks'].append(recovered);recoveredstate=wait(recovered)
 report['checks']['nativeActorRecreatedAfterCancellation']=recoveredstate['status']=='completed' and recoveredstate['worker_pid']!=running['worker_pid']
 with sqlite3.connect(a.workspace/'tasks.sqlite3') as db:
  db.row_factory=sqlite3.Row;ledger=[dict(row) for row in db.execute('SELECT id,filename,status,error,created_at,started_at,finished_at,worker_pid FROM tasks WHERE id IN ('+','.join('?'*len(report['tasks']))+')',report['tasks'])]
 report['checks']['sqliteTaskLedgerCrossCheck']=len(ledger)==len(report['tasks']) and {row['id']:row['status'] for row in ledger}=={task:api('/tasks/'+task)['status'] for task in report['tasks']}
 report['ledger']=ledger;report['status']='PASS_ACTUAL_MINERU_SERVICE' if all(report['checks'].values()) else 'FAIL_ACTUAL_MINERU_SERVICE';report['boundary']='Actual DOCX/native parser and owned worker cancellation. PDF hybrid request is truthfully DEPENDENCY_NOT_AVAILABLE; no hybrid parse/model-quality success. Production 600s config checked; this batch does not wait 600s.'
except Exception as error:
 import traceback;(a.output/'failure.log').write_text(traceback.format_exc());report['error']=str(error)
report['requests']=requests;report['sourceHashes']={str(path.relative_to(R)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [Path(__file__),R/'scripts/mineru-service/app.py']};report['inputSha256']=hashlib.sha256(a.fixture.read_bytes()).hexdigest()
(a.output/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({key:report[key] for key in ['status','checks','tasks']},ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
