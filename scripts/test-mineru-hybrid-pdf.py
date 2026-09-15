"""Actual one-page hybrid PDF inference with independent input/business/location checks."""
import argparse,hashlib,json,os,sqlite3,subprocess,time,urllib.error,urllib.request,uuid
from datetime import datetime,timezone
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:18489');p.add_argument('--workspace',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence directory required')
a.output.mkdir(parents=True);original=a.output/'hybrid-contract-table.pdf';c=canvas.Canvas(str(original),pagesize=A4);c.setFont('Helvetica-Bold',16);c.drawString(45,790,'Contract HT-900: payment and warranty');c.setFont('Helvetica',12);c.drawString(45,758,'Synthetic acceptance fixture. Amount unit: CNY 10,000.');c.drawString(45,734,'Payment requires BOTH a valid invoice AND completed acceptance.');c.drawString(45,710,'Pay within 60 days after both conditions. Warranty: 24 months.');
xs=[45,150,285,440,550];ys=[660,625,590,555]
for x in xs:c.line(x,ys[-1],x,ys[0])
for y in ys:c.line(xs[0],y,xs[-1],y)
for row,values in enumerate([['Contract','Amount','Warranty','Days'],['HT-900','900.00','24 months','60'],['HT-902','120.50','12 months','30']]):
 for col,value in enumerate(values):c.drawString(xs[col]+8,ys[row]-23,value)
c.drawString(45,525,'No invoice: no payment obligation. Do not drop this condition.');c.save()
report=dict(status='RUNNING_ACTUAL_HYBRID_PDF',startedAt=datetime.now(timezone.utc).isoformat(),checks={},samples=[],input=dict(path=str(original),sha256=hashlib.sha256(original.read_bytes()).hexdigest(),pages=1,criticalFields=['HT-900','900.00','24','60','HT-902','120.50','12','30','invoice','acceptance'],goldPageSize=[595.275590551,841.88976378],goldTableRegion=[45,555,550,660]),boundary='Actual native MinerU345 hybrid-engine/medium/auto. No embedding/chat calls. One digital PDF only; no claim for scans, every PDF or business output beyond independent checks.');
def save():(a.output/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str))
def api(path,data=None,method=None,ctype=None):
 request=urllib.request.Request(a.url+path,data=data,method=method,headers={'Content-Type':ctype} if ctype else {})
 with urllib.request.urlopen(request,timeout=20) as response:return json.load(response)
save();started=time.monotonic();task=None
try:
 report['ready']=api('/ready');boundary='pdf-native-'+uuid.uuid4().hex;parts=[]
 for key,value in [('backend','hybrid-engine'),('effort','medium'),('parse_method','auto'),('lang_list','ch')]:parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
 parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{original.name}"\r\nContent-Type: application/pdf\r\n\r\n'.encode()+original.read_bytes()+f'\r\n--{boundary}--\r\n'.encode());submitted=api('/tasks',b''.join(parts),'POST','multipart/form-data; boundary='+boundary);task=submitted['task_id'];report['taskId']=task;save()
 while time.monotonic()-started<625:
  state=api('/tasks/'+task);report['state']=state;pid=state.get('worker_pid')
  if pid:
   ps=subprocess.run(['ps','-axo','pid=,pgid=,rss=,%cpu=,time=,stat='],capture_output=True,text=True);owned=[line.strip() for line in ps.stdout.splitlines() if len(line.split())>=6 and (line.split()[0]==str(pid) or line.split()[1]==str(pid))];resident=sum(int(line.split()[2]) for line in owned if not line.split()[-1].startswith('Z'));report['samples'].append(dict(elapsedSeconds=round(time.monotonic()-started,3),actualOwnedProcessGroup=owned,ownedGroupRssKiB=resident))
   if resident>6*1024*1024:
    report['cancellation']=api('/tasks/'+task,method='DELETE');raise MemoryError('Owned parser worker exceeded 6 GiB RSS resource guard; explicitly cancelled')
  save()
  if state['status'] in ('completed','failed','cancelled'):break
  time.sleep(3)
 else:
  report['cancellation']=api('/tasks/'+task,method='DELETE');raise TimeoutError('Service task did not reach terminal within normal 600-second deadline plus polling allowance')
 if state['status']!='completed':raise RuntimeError('Actual parser failed: '+json.dumps(state.get('error'),ensure_ascii=False))
 result=api('/tasks/'+task+'/result');(a.output/'native-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));file=next(iter(result['results'].values()));text=json.dumps(file['content_list'],ensure_ascii=False).lower();checks=report['checks'];checks['actualFrozenParserProfile']=result['version']=='3.4.5' and result['effective_backend']=='hybrid-engine' and result['parser_profile']['effort']=='medium' and result['parser_profile']['parseMethod']=='auto' and result['parser_profile']['modelSource']=='local';checks['exactOriginalMatchesReceipt']=result['parser_profile']['sourceSha256']==report['input']['sha256'];checks['independentCriticalValuesPreserved']=all(field.lower() in text for field in report['input']['criticalFields']);checks['bothPaymentPrerequisitesRemain']=all(field in text for field in ['invoice','acceptance','both'])
 pages=file['middle_json']['pdf_info'];checks['allActualPagesHaveContent']=len(pages)==1 and pages[0]['page_idx']==0 and bool(pages[0]['para_blocks']);tables=[item for item in file['content_list'] if item.get('type')=='table'];checks['actualTableRetainsHeaderAndUnit']=bool(tables) and any(all(value in json.dumps(table).lower() for value in ['contract','amount','warranty','900','120.50']) for table in tables) and '10,000' in text
 locations=[item for item in file['content_list'] if item.get('bbox')];checks['actualPdfOriginalLocations']=bool(locations) and all(item.get('page_idx')==0 and len(item['bbox'])==4 and all(isinstance(x,(int,float)) and 0<=x<=1000 for x in item['bbox']) for item in locations)
 with sqlite3.connect(a.workspace/'tasks.sqlite3') as db:
  db.row_factory=sqlite3.Row;row=dict(db.execute('SELECT id,status,worker_pid,created_at,started_at,finished_at,error FROM tasks WHERE id=?',(task,)).fetchone());report['ledger']=row;checks['normalServiceLedgerMatchesActualTask']=row['status']=='completed' and row['worker_pid']==state['worker_pid'] and bool(row['finished_at'])
 report['status']='PASS_ACTUAL_HYBRID_PDF' if all(checks.values()) else 'FAIL_ACTUAL_HYBRID_PDF'
except Exception as error:
 import traceback;(a.output/'failure.log').write_text(traceback.format_exc());report.update(status='FAIL_ACTUAL_HYBRID_PDF',failureType=type(error).__name__,failure=str(error))
report.update(finishedAt=datetime.now(timezone.utc).isoformat(),elapsedSeconds=round(time.monotonic()-started,3),scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest());save();print(json.dumps(dict(status=report['status'],checks=report['checks'],taskId=task,elapsedSeconds=report['elapsedSeconds']),ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
