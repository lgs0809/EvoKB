#!/usr/bin/env python3
"""Read-only native MinIO trace, scoped to one owned object, with immediate redaction.

Only method/function/status/time/partNumber and matching object path are saved. Raw trace
headers, bodies, credentials and signed URL queries never go to an evidence file/stdout.
The official mc already installed in the existing MinIO container is reused.
"""
import argparse,datetime,http.cookiejar,json,os,re,select,subprocess,time,urllib.parse,urllib.request
from pathlib import Path

R=Path(__file__).resolve().parents[1];p=argparse.ArgumentParser(description=__doc__);p.add_argument('--preparation',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--seconds',type=int,default=180);p.add_argument('--read-probe',action='store_true');a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence required')
prepared=json.loads(a.preparation.read_text());session=prepared['actualView']['session'];sid=session['session_id'];assert re.fullmatch('[a-f0-9-]{36}',sid)
path=session['bucket']+'/'+session['object_key'];settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
env=dict(os.environ,MC_HOST_resume='http://'+urllib.parse.quote(settings['EKB_MINIO_ROOT_USER'],safe='')+':'+urllib.parse.quote(settings['EKB_MINIO_ROOT_PASSWORD'],safe='')+'@127.0.0.1:9000')
started=time.monotonic();records=[];report={'status':'RUNNING_NATIVE_OBJECT_TRACE','sessionId':sid,'objectPath':path,'preparation':str(a.preparation.resolve()),'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'boundary':'Actual native storage trace for this exact object; no network mock or request interception. Saves only allowlisted protocol facts; trace credentials/signatures/headers/body are discarded immediately. API preparation and subsequent CUA remain separate.'}
def save(status):report.update(status=status,records=records,elapsedSeconds=time.monotonic()-started);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
traceBuffer=''
def permit_record(item):
 def schema(value):
  if isinstance(value,dict):return {key:schema(v) for key,v in value.items()}
  if isinstance(value,list):return ['array']
  return type(value).__name__
 if 'nativeRecordSchema' not in report:report['nativeRecordSchema']=schema(item)
 req=item.get('request',{}) if isinstance(item.get('request'),dict) else {}
 resp=item.get('response',{}) if isinstance(item.get('response'),dict) else {}
 url=str(item.get('path') or req.get('path') or '')
 parsed=urllib.parse.urlsplit(url);clean=urllib.parse.unquote(parsed.path).lstrip('/')
 if clean!=path:return None
 query=urllib.parse.parse_qs(item.get('query') or req.get('rawQuery') or parsed.query)
 return {'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'actualCallAt':item.get('time'),'method':item.get('method') or req.get('method'), 'function':item.get('api'), 'statusCode':item.get('statusCode') or resp.get('statusCode'), 'path':clean,'partNumber':query.get('partNumber',[None])[0]}
def permitted(chunk):
 global traceBuffer
 # Consume raw fd chunks. TextIO.readline + select loses already-buffered JSON lines.
 traceBuffer+=chunk
 if len(traceBuffer)>1048576:raise RuntimeError('Native trace JSON exceeds bounded transient buffer')
 permittedRecords=[]
 while traceBuffer.strip():
  traceBuffer=traceBuffer.lstrip()
  try:item,end=json.JSONDecoder().raw_decode(traceBuffer)
  except ValueError:break
  traceBuffer=traceBuffer[end:]
  value=permit_record(item)
  if value:permittedRecords.append(value)
 return permittedRecords
process=subprocess.Popen(['docker','exec','-e','MC_HOST_resume','evokb-acceptance-minio-1','sh','-c','echo $$ >&2; exec mc --json admin trace resume','trace',path],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
remotePid=None;probeDone=False
try:
 save('RUNNING_NATIVE_OBJECT_TRACE')
 while time.monotonic()-started<a.seconds:
  if process.poll() is not None:raise RuntimeError('Native trace exited code='+str(process.returncode))
  ready,_,_=select.select([process.stdout,process.stderr],[],[],0.2)
  for stream in ready:
   if stream is process.stderr:
    line=stream.readline()
    if remotePid is None and line.strip().isdigit():remotePid=int(line.strip());report['ownedContainerTracePid']=remotePid
    else:report['nativeTraceStderrObserved']=True
   else:
    chunk=os.read(stream.fileno(),65536).decode('utf-8')
    values=permitted(chunk)
    if values:records.extend(values);save('RUNNING_NATIVE_OBJECT_TRACE')
  if a.read_probe and not probeDone and time.monotonic()-started>2:
   opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));account=json.loads((R/'.tmp/target/accounts.json').read_text())['admin']
   with opener.open(urllib.request.Request('http://127.0.0.1:18481/api/enterprise/auth/login',data=json.dumps(account).encode(),headers={'Content-Type':'application/json'}),timeout=15) as response:assert response.status==200
   with opener.open('http://127.0.0.1:18481/api/enterprise/upload-sessions/'+sid,timeout=15) as response:assert response.status==200
   nativeHead=subprocess.run(['docker','exec','-e','MC_HOST_resume','evokb-acceptance-minio-1','mc','--json','stat','resume/'+path],env=env,capture_output=True,text=True,timeout=15);report['nativeReadProbeHeadExitCode']=nativeHead.returncode
   probeDone=True
  if a.read_probe and records:break
 report['status']='PASS_NATIVE_TRACE_READ_PROBE' if a.read_probe and records else 'COMPLETE_NATIVE_OBJECT_TRACE' if records else 'FAIL_NATIVE_OBJECT_TRACE_EMPTY'
except Exception as error:report['status']='FAIL_NATIVE_OBJECT_TRACE';report['failure']=type(error).__name__+': '+str(error)
finally:
 if remotePid:
  stopped=subprocess.run(['docker','exec','evokb-acceptance-minio-1','sh','-c','kill -TERM "$1"','stop-trace',str(remotePid)],capture_output=True,text=True,timeout=10);report['ownedRemoteTraceTerminationRequested']=stopped.returncode==0
 try:process.wait(timeout=5)
 except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=5)
 report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();save(report['status'])
print(json.dumps({'status':report['status'],'records':len(records),'output':str(a.output)},ensure_ascii=False));raise SystemExit(1 if report['status'].startswith('FAIL') else 0)
