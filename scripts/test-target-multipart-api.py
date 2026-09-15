"""Normal authenticated target API/real signed PUT/real worker integrity failure, no DB mutation."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
import base64,hashlib,http.cookiejar,json,subprocess,time,urllib.error,urllib.request,uuid
from pathlib import Path
R=Path(__file__).resolve().parents[1];BASE='http://127.0.0.1:18481/api/enterprise'
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--jar-sha256',required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh report required')
state=json.loads((R/'.tmp/target/process.json').read_text());assert state['jarSha256']==a.jar_sha256,'Verify against actually deployed immutable artifact'
a.output.parent.mkdir(parents=True,exist_ok=True);checks={};observations=[];report=dict(status='RUNNING_NORMAL_MULTIPART_API',deployedArtifact=state,startedAt=datetime.now(timezone.utc).isoformat(),boundary='Normal authenticated HTTP APIs and native presigned MinIO PUT. Wrong whole SHA intentionally triggers normal Raw worker isolation, not a model/parsing success. Database reads below are read-only; no status manufacture.')
def save(status):report.update(status=status,checks=checks,observations=observations);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
def client(account=None):
 opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def request(path,data=None):
  req=urllib.request.Request(BASE+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
  try:
   with opener.open(req,timeout=30) as response:status,body=response.status,json.load(response)
  except urllib.error.HTTPError as error:status,body=error.code,json.load(error)
  observations.append(dict(path=path,status=status,at=datetime.now(timezone.utc).isoformat()))
  return status,body
 if account:
  status,body=request('/auth/login',account);assert status==200 and body['user']['user_id']==account['user_id'],'Normal login failed'
 return request
accounts=json.loads((R/'.tmp/target/accounts.json').read_text());admin=client(accounts['admin']);reader=client(accounts['reader']);anonymous=client();other=None
private=R/'.tmp/target/permission-accounts.json'
if private.exists():
 alt=json.loads(private.read_text()).get('admin')
 if alt and alt['user_id']!=accounts['admin']['user_id']:other=client(alt)
def payload(name,size,sha=''):
 return dict(filename=name+'.txt',relative_path=name+'.txt',size_bytes=size,client_sha256=sha,metadata=dict(logical_name=name,source_department='合成验收',material_category='哈希与上传边界',company='',document_type='验收记录',document_date=None,manual_tags=['multipart-api']))
def query(sql):
 result=subprocess.run(['docker','exec','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-At','-c',sql],capture_output=True,text=True,timeout=10);result.check_returncode();return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
def put(signed,data,md5):
 req=urllib.request.Request(signed['url'],data=data,method='PUT',headers={'Content-MD5':md5,'Origin':'http://127.0.0.1:18481'})
 try:
  with urllib.request.urlopen(req,timeout=30) as response:return response.status,response.read().decode(),dict(response.headers)
 except urllib.error.HTTPError as error:return error.code,error.read().decode(),dict(error.headers)
try:
 code,policy=admin('/upload-sessions/policy');assert code==200
 checks['effective256MiB16MiB4Parallel600Second24HourPolicy']=policy['max_file_bytes']==268435456 and policy['part_size']==16777216 and policy['parallel_parts']==4 and policy['url_seconds']==600 and policy['session_seconds']==86400
 checks['anonymousCannotCreateOrReadSessionPolicy']=anonymous('/upload-sessions/policy')[0]==401
 checks['nonUploaderCannotCreateUploadSession']=reader('/upload-sessions',payload('reader-denied',10))[0]==403
 for label,size in [('empty',0),('above-effective-limit',policy['max_file_bytes']+1)]:checks['reject-'+label]=admin('/upload-sessions',payload('reject-'+label,size))[0]==400
 bad=payload('unsafe',10);bad['filename']='../original.txt';checks['reject-traversal-filename']=admin('/upload-sessions',bad)[0]==400
 bad=payload('unsafe',10);bad['relative_path']='x/../original.txt';checks['reject-traversal-relative-path']=admin('/upload-sessions',bad)[0]==400
 bad=payload('unsafe',10);bad['client_sha256']='not-a-digest';checks['reject-malformed-whole-sha']=admin('/upload-sessions',bad)[0]==400
 bad=payload('unsafe',10);bad['filename']='executable.exe';checks['reject-unsupported-source-type']=admin('/upload-sessions',bad)[0]==400
 text='合成哈希验收：应在完成验收且收到合法发票后付款。'.encode();whole=hashlib.sha256(text).hexdigest();part_md5=base64.b64encode(hashlib.md5(text).digest()).decode();name='multipart-api-'+uuid.uuid4().hex[:10]
 code,session=admin('/upload-sessions',payload(name,len(text),whole));assert code==201;sid=session['session_id']
 checks['newSessionHasAbsolute24HourLifetime']=abs((datetime.fromisoformat(session['expires_at'].replace('Z','+00:00'))-datetime.fromisoformat(session['created_at'].replace('Z','+00:00'))).total_seconds()-86400)<0.001
 if other:checks['anotherAuthorizedUploaderCannotReadSession']=other('/upload-sessions/'+sid)[0]==403
 checks['reject-wrong-reselected-sha']=admin('/upload-sessions/'+sid+'/part-url',dict(number=1,content_md5=part_md5,client_sha256='0'*64))[0]==409
 checks['reject-out-of-range-part-number']=admin('/upload-sessions/'+sid+'/part-url',dict(number=2,content_md5=part_md5,client_sha256=whole))[0]==400
 checks['reject-missing-parts-before-completion']=admin('/upload-sessions/'+sid+'/complete',dict(revision=session['revision'],parts=[]))[0]==409
 code,signed=admin('/upload-sessions/'+sid+'/part-url',dict(number=1,content_md5='AAAAAAAAAAAAAAAAAAAAAA==',client_sha256=whole));assert code==200
 status,body,headers=put(signed,text,'AAAAAAAAAAAAAAAAAAAAAA==');checks['actualNativeStorageRejectsWrongPartMd5']=status==400 and 'BadDigest' in body
 code,view=admin('/upload-sessions/'+sid);checks['rejectedPartDoesNotAppearInStorageResumeFacts']=code==200 and view['uploaded_parts']==[]
 code,cancelled=admin('/upload-sessions/'+sid+'/cancel',dict(revision=view['session']['revision']));checks['normalCancelAbortsOwnedMultipart']=code==200 and cancelled['status']=='ABORTED' and cancelled['abort_confirmed']
 wrong_name='multipart-wrong-sha-'+uuid.uuid4().hex[:10]
 code,wrong=admin('/upload-sessions',payload(wrong_name,len(text),'0'*64));assert code==201;wid=wrong['session_id']
 code,signed=admin('/upload-sessions/'+wid+'/part-url',dict(number=1,content_md5=part_md5,client_sha256='0'*64));assert code==200
 status,body,headers=put(signed,text,part_md5);checks['normalSignedPutCompletesPartWithCorsEtag']=status==200 and headers.get('Access-Control-Allow-Origin')=='http://127.0.0.1:18481' and 'etag' in headers.get('Access-Control-Expose-Headers','').lower()
 code,view=admin('/upload-sessions/'+wid);assert code==200
 part= dict(revision=view['session']['revision'],parts=view['uploaded_parts'])
 with ThreadPoolExecutor(max_workers=4) as pool:completion=list(pool.map(lambda _:admin('/upload-sessions/'+wid+'/complete',part),range(8)))
 checks['concurrentNormalCompleteRequestsReturnOnlyOwnedState']=all(code==200 and value['session_id']==wid and value['status'] in ['COMPLETING','COMPLETED'] for code,value in completion)
 code,final=admin('/upload-sessions/'+wid);assert code==200;finished=final['session'];assert finished['status']=='COMPLETED'
 repeated=admin('/upload-sessions/'+wid+'/complete',part);checks['normalDuplicateCompleteReusesSameVersionAndTask']=repeated[0]==200 and repeated[1]['document_version_id']==finished['document_version_id'] and repeated[1]['file_id']==finished['file_id']
 sql="SELECT row_to_json(s)::text FROM kb_upload_session s WHERE session_id='"+wid+"';\nSELECT row_to_json(f)::text FROM ingestion_file f WHERE file_id='"+finished['file_id']+"';\nSELECT json_build_object('versions',count(*))::text FROM document_version WHERE version_id='"+finished['document_version_id']+"';\nSELECT json_build_object('published',count(*))::text FROM raw_publication WHERE version_id='"+finished['document_version_id']+"';"
 a.output.with_suffix('.readback.sql').write_text(sql);start=time.monotonic()
 while time.monotonic()-start<45:
  rows=query(sql);save('RUNNING_NORMAL_RAW_SHA_ISOLATION')
  if rows[1]['status'] in ['FAILED','SUCCEEDED']:break
  time.sleep(1)
 report['actualWorkerReadback']=rows
 checks['normalWorkerRejectsWrongWholeShaBeforeModelOrPublication']=rows[1]['status']=='FAILED' and rows[1]['attempt']==1 and rows[1]['error_code']=='INPUT_ERROR' and rows[0]['integrity_status']=='FAILED' and rows[0]['actual_sha256']==whole and rows[3]['published']==0
 checks['normalConcurrencyCreatesExactlyOneReservedVersion']=rows[2]['versions']==1
 report['finishedAt']=datetime.now(timezone.utc).isoformat();save('PASS_NORMAL_MULTIPART_API' if all(checks.values()) else 'FAIL_NORMAL_MULTIPART_API')
except Exception as error:
 report['failure']=type(error).__name__+': '+str(error);save('FAIL_NORMAL_MULTIPART_API')
print(json.dumps(dict(status=report['status'],passed=sum(checks.values()),failed=[name for name,value in checks.items() if not value],output=str(a.output)),ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
