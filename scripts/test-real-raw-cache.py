"""Normal batch APIs plus read-only native probe against exact immutable deployed JAR."""
from native_probe_runtime import java17_bin
import argparse,hashlib,io,json,os,subprocess,tempfile,time,urllib.error,urllib.request,uuid,zipfile,http.cookiejar
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1];BASE='http://127.0.0.1:18481/api/enterprise'
def now():return datetime.now(timezone.utc).isoformat()
def main():
 p=argparse.ArgumentParser();p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--timeout',type=int,default=660);a=p.parse_args()
 if a.output.exists():raise SystemExit('Fresh evidence path required')
 a.output.mkdir(parents=True);fixtures=json.loads(a.fixtures.read_text());state=json.loads((R/'.tmp/target/process.json').read_text());jar=Path(state['artifact']).read_bytes()
 if hashlib.sha256(jar).hexdigest()!=state['jarSha256']:raise SystemExit('Deployed artifact hash mismatch')
 report=dict(status='RUNNING_REAL_RAW_ACCEPTANCE',startedAt=now(),deployedArtifact=state,fixtureManifestSha256=hashlib.sha256(a.fixtures.read_bytes()).hexdigest(),runs=[],checks={},boundary='Normal batch APIs; actual native Office/Qwen/PG/Milvus. Shared global HTTP logs cannot attribute concurrent background requests to this task; automatic Summary generation is still the old route and is not selective-generation acceptance.')
 def persist(): (a.output/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str))
 persist();account=json.loads((R/'.tmp/target/accounts.json').read_text())['admin'];opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def request(path,data=None,method=None,ctype='application/json'):
  raw=data if isinstance(data,bytes) else None if data is None else json.dumps(data).encode();req=urllib.request.Request(BASE+path,data=raw,headers={'Content-Type':ctype},method=method)
  try:
   with opener.open(req,timeout=30) as response:return response.status,json.load(response)
  except urllib.error.HTTPError as error:return error.code,json.load(error)
 status,user=request('/auth/login',account)
 if status!=200 or user['user']['user_id']!=account['user_id']:raise SystemExit('Normal login failed')
 settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'));java=java17_bin();source=R/'scripts/RawEncodingReadbackProbe.java';sourcebytes=source.read_bytes()
 with tempfile.TemporaryDirectory(prefix='evokb-deployed-cache-readback-') as directory:
  d=Path(directory);libs=[]
  with zipfile.ZipFile(io.BytesIO(jar)) as archive:
   for entry in archive.namelist():
    if entry.startswith('BOOT-INF/classes/') and not entry.endswith('/'):
     target=d/'boot'/entry[len('BOOT-INF/classes/'):];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(entry))
    elif entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
     target=d/'lib'/Path(entry).name;target.parent.mkdir(exist_ok=True);target.write_bytes(archive.read(entry));libs.append(target)
  cp=os.pathsep.join([str(d/'boot'),*(str(x) for x in libs)]);probe=d/source.name;probe.write_bytes(sourcebytes)
  compiled=subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(d),str(probe)],capture_output=True,text=True);(a.output/'compile.log').write_text(compiled.stdout+compiled.stderr)
  if compiled.returncode:report.update(status='FAIL_PROBE_COMPILE');persist();raise SystemExit(1)
  env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?stringtype=unspecified')
  try:
   for index,fixture in enumerate([fixtures['versions'][0],fixtures['versions'][0],fixtures['versions'][1],fixtures['versions'][2]]):
    run=dict(index=index,fixtureVersion=fixture['version'],fixtureSha256=fixture['sha256'],startedAt=now(),observations=[]);report['runs'].append(run);persist()
    raw=Path(fixture['path']).read_bytes()
    if hashlib.sha256(raw).hexdigest()!=fixture['sha256']:raise ValueError('Fixture bytes changed')
    boundary='evokb-real-cache-'+uuid.uuid4().hex;body=(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{fixture["filename"]}"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n').encode()+raw+f'\r\n--{boundary}--\r\n'.encode()
    status,created=request('/batches',body,ctype='multipart/form-data; boundary='+boundary);run['uploadHttpStatus']=status;run['created']=created;persist()
    if status!=201:raise ValueError('Normal create batch failed')
    batch=created['batch'];batchid=batch['batch_id'];fileid=batch['files'][0]['file_id']
    status,patched=request('/batch/file?id='+fileid,{'logical_name':fixtures['logicalName'],'source_department':'合成验收','material_category':'合同','company':'合成验收公司','document_type':'合同','manual_tags':['raw-cache-real']},method='PATCH');run['patchHttpStatus']=status
    if status!=200:raise ValueError('Normal metadata patch failed')
    status,started=request('/batch/start',{'batch_id':batchid});run['queueHttpStatus']=status;run['started']=started;persist()
    if status!=200:raise ValueError('Normal queue failed')
    deadline=time.monotonic()+a.timeout;previous=None
    while time.monotonic()<deadline:
     status,view=request('/batch?id='+batchid)
     if status!=200:raise ValueError('Batch polling failed')
     file=view['files'][0];observed={key:file.get(key) for key in ['status','progress_stage','attempt','document_id','version_id','error_code','error_message','started_at','finished_at']}
     if observed!=previous:run['observations'].append(dict(observedAt=now(),**observed));previous=observed;persist();print(json.dumps(dict(index=index,**observed),ensure_ascii=False),flush=True)
     if file['status'] in ('SUCCEEDED','FAILED'):run['final']=view;break
     time.sleep(2)
    else:raise TimeoutError('Actual batch did not reach terminal within observation window; task is not fabricated/cancelled')
    run['finishedAt']=now();log=subprocess.run(['docker','logs','--timestamps','--since',run['startedAt'],'--until',run['finishedAt'],'embedding-model'],capture_output=True,text=True)
    access=[line for line in (log.stdout+log.stderr).splitlines() if '"POST /v1/embeddings ' in line];(a.output/f'run-{index}-global-embedding-access.log').write_text('\n'.join(access));run['sharedGlobalEmbeddingHttpRequestCount']=len(access)
    if file['status']!='SUCCEEDED':raise ValueError('Actual ingestion failed: '+str(file.get('error_code')))
    output=a.output/f'run-{index}-readback.json';executed=subprocess.run([str(java/'java'),'-XX:ActiveProcessorCount=2','-Xmx256m','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(d)+os.pathsep+cp,'cn.lgs.knowledgebase.application.raw.RawEncodingReadbackProbe',str(output),fixtures['logicalName'],settings['EKB_EMBED_TOKENIZER_MANIFEST']],env=env,capture_output=True,text=True,timeout=35);(a.output/f'run-{index}-readback.log').write_text(executed.stdout+executed.stderr)
    run['readback']=json.loads(output.read_text());persist()
    if executed.returncode:raise ValueError('Actual raw readback failed')
   reads=[run['readback']['versions'] for run in report['runs']];checks=report['checks'];checks['sameOriginalReusesSameVersion']=report['runs'][0]['final']['files'][0]['version_id']==report['runs'][1]['final']['files'][0]['version_id'] and len(reads[1])==1
   checks['changedOriginalCreatesVersionsThroughNormalFlow']=[len(rows) for rows in reads]==[1,1,2,3] and len({v['document_id'] for v in reads[-1]})==1
   v1,v2,v3=reads[-1];by1={c['inputHash']:c for c in v1['chunks']};by2={c['inputHash']:c for c in v2['chunks']};by3={c['inputHash']:c for c in v3['chunks']};shared=set(by1)&set(by2);checks['unchangedFullInputsReuseExactCacheRows']=bool(shared) and all(by1[h]['cacheRows']==by2[h]['cacheRows'] for h in shared)
   checks['changedConditionCreatesNewInputCache']=bool(set(by2)-set(by1));checks['titleChangeInvalidatesEveryFullInput']=not(set(by2)&set(by3)) and len(by3)>0
   checks['titleChangeCanPreserveBodyHash']=bool({b['contentHash'] for b in v2['blockHashes']}&{b['contentHash'] for b in v3['blockHashes']})
   checks['onlyLatestVersionActive']=[v['lifecycle'] for v in reads[-1]]==['INACTIVE','INACTIVE','ACTIVE'];checks['everyActualReadbackPasses']=all(run['readback']['status'].startswith('PASS') for run in report['runs'])
   report['status']='PASS_REAL_RAW_API_AND_CACHE_IDENTITY' if all(checks.values()) else 'FAIL_REAL_RAW_API_AND_CACHE_IDENTITY'
  except Exception as error:report.update(status='FAIL_REAL_RAW_API_AND_CACHE_IDENTITY',failureType=type(error).__name__,failure=str(error))
 report.update(finishedAt=now(),probeSourceSha256=hashlib.sha256(sourcebytes).hexdigest());persist();print(json.dumps(dict(status=report['status'],checks=report['checks']),ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
if __name__=='__main__':main()
