#!/usr/bin/env python3
"""Prepare two real parts through normal APIs; leave completion for the actual browser."""
import argparse,base64,datetime,hashlib,http.cookiejar,json,urllib.error,urllib.request
from pathlib import Path

R=Path(__file__).resolve().parents[1];BASE='http://127.0.0.1:18481/api/enterprise'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--original',type=Path,required=True);p.add_argument('--logical-name',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--existing-session');a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh output required; no session is silently replaced')
digest=hashlib.sha256()
with a.original.open('rb') as f:
 for data in iter(lambda:f.read(1048576),b''):digest.update(data)
whole=digest.hexdigest();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
report={'status':'RUNNING_NORMAL_RESUME_PREPARATION','deployedArtifact':json.loads((R/'.tmp/target/process.json').read_text()),'original':str(a.original.resolve()),'bytes':a.original.stat().st_size,'sha256':whole,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'boundary':'Normal authenticated session creation and two actual native signed PUTs. Completion is deliberately left to the normal browser. This preparation is API evidence, never claimed as pure CUA or completed Raw.'};observations=[];checks={}
def save(status):report.update(status=status,observations=observations,checks=checks);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
def request(path,data=None):
 req=urllib.request.Request(BASE+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
 with opener.open(req,timeout=30) as response:code=response.status;body=json.load(response)
 observations.append({'path':path,'status':code,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()});return code,body
try:
 account=json.loads((R/'.tmp/target/accounts.json').read_text())['admin'];code,auth=request('/auth/login',account);assert code==200 and auth['user']['user_id']==account['user_id']
 code,policy=request('/upload-sessions/policy');assert code==200;partSize=policy['part_size'];assert report['bytes']>partSize*4
 metadata={'logical_name':a.logical_name,'source_department':'合成验收','material_category':'分片传输与原件完整性','company':'','document_type':'合同','document_date':None,'manual_tags':['raw-browser-resume','付款与质保']}
 if a.existing_session:
  code,existing=request('/upload-sessions/'+a.existing_session);assert code==200;session=existing['session'];assert session['status']=='UPLOADING' and session['filename']==a.original.name and session['size_bytes']==report['bytes'] and session['client_sha256']==whole
  stored={x['number']:x for x in existing['uploaded_parts']}
 else:
  code,session=request('/upload-sessions',{'filename':a.original.name,'relative_path':a.original.name,'size_bytes':report['bytes'],'client_sha256':whole,'metadata':metadata});assert code==201;stored={}
 report['session']=session;save('RUNNING_TWO_REAL_PART_PUTS')
 expected=[]
 with a.original.open('rb') as original:
  for number in [1,2]:
   data=original.read(partSize);assert len(data)==partSize
   md5=base64.b64encode(hashlib.md5(data).digest()).decode()
   if number in stored:
    part=stored[number];assert part['size_bytes']==len(data) and part['etag']==hashlib.md5(data).hexdigest();expected.append(part);checks['existingActualPart'+str(number)+'MatchesFrozenOriginalWithoutPut']=True;continue
   code,signed=request('/upload-sessions/'+session['session_id']+'/part-url',{'number':number,'content_md5':md5,'client_sha256':whole});assert code==200
   req=urllib.request.Request(signed['url'],data=data,method='PUT',headers={'Content-MD5':md5,'Origin':'http://127.0.0.1:18481'})
   with urllib.request.urlopen(req,timeout=45) as response:assert response.status==200;etag=response.headers['ETag'].strip('"');origin=response.headers['Access-Control-Allow-Origin']
   expected.append({'number':number,'size_bytes':len(data),'etag':etag});checks['actualPart'+str(number)+'HasNativeMd5AndCors']=etag==hashlib.md5(data).hexdigest() and origin=='http://127.0.0.1:18481';save('RUNNING_TWO_REAL_PART_PUTS')
 code,view=request('/upload-sessions/'+session['session_id']);assert code==200;report['actualView']=view
 checks['twoActualStoragePartsAndOnlyRemainingThreeMissing']=view['uploaded_parts']==expected and view['session']['part_count']==5
 checks['sameWholeOriginalAndMetadataPinned']=view['session']['client_sha256']==whole and dict(view['session']['metadata'],document_date=view['session']['metadata'].get('document_date'))==metadata
 checks['uploadNotMergedAndNoBusinessFileTaskCreated']=view['session']['status']=='UPLOADING' and not view['session']['batch_id'] and not view['session']['file_id']
 report['preparedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();save('PASS_NORMAL_TWO_PARTS_READY_FOR_CUA' if all(checks.values()) else 'FAIL_NORMAL_TWO_PART_PREPARATION')
except Exception as error:report['failure']=type(error).__name__+': '+str(error);save('FAIL_NORMAL_TWO_PART_PREPARATION')
print(json.dumps({'status':report['status'],'sessionId':report.get('session',{}).get('session_id'),'checks':checks,'output':str(a.output)},ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
