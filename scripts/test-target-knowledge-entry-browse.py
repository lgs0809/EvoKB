#!/usr/bin/env python3
"""Normal authenticated read-only entry browsing and evidence; no SQL/model mutations."""
import argparse,datetime,http.cookiejar,json,re,subprocess,urllib.error,urllib.parse,urllib.request,uuid
from pathlib import Path

R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--jar-sha256',required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh output required')
state=json.loads((R/'.tmp/target/process.json').read_text())
if state['jarSha256']!=a.jar_sha256:raise SystemExit('Bind to actually deployed immutable artifact')
accounts=json.loads((R/'.tmp/target/accounts.json').read_text());checks={};observations=[]
report={'status':'RUNNING_ACTUAL_ENTRY_BROWSE','deployedArtifact':state,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'boundary':'Actual authenticated GET APIs and current evidence. No direct DB writes, generation/model requests, ACL changes, or manufactured workflow outcomes. Synthetic ACL edge coverage remains in isolated native PG probe.'}
def save(status):report.update(status=status,checks=checks,observations=observations);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
def client(account=None):
 opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def request(path,body=None):
  req=urllib.request.Request('http://127.0.0.1:18481/api/enterprise'+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
  try:
   with opener.open(req,timeout=15) as response:
    raw=response.read();code=response.status;result=json.loads(raw) if raw else None
  except urllib.error.HTTPError as error:
   raw=error.read();code=error.code
   try:result=json.loads(raw) if raw else None
   except ValueError:result=None
  observations.append({'path':path,'status':code,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()});return code,result
 if account:
  status,result=request('/auth/login',account);assert status==200 and result['user']['user_id']==account['user_id']
 return request
try:
 admin=client(accounts['admin']);reader=client(accounts['reader']);anonymous=client()
 checks['anonymousListAndDetailRequireAuthentication']=anonymous('/knowledge-entries')[0]==401 and anonymous('/knowledge-entry?id='+str(uuid.uuid4()))[0]==401
 status,result=admin('/knowledge-entries?page=1&page_size=100');assert status==200 and result['entries'],'Actual current entries required for non-empty acceptance'
 report['actualEntries']=result
 checks['currentEntriesExposeDistinctIdentityScopeAndStage']=all(e['entry_id'] and e['revision']>0 and e['stage'] in ['CONCEPT','WIKI'] and e['lifecycle']=='ACTIVE' and e['source_count']>0 and e['scope'] and isinstance(e['aliases'],list) for e in result['entries'])
 checks['listIsBoundedAndCountNotSmallerThanActualPage']=len(result['entries'])<=100 and result['page_size']==100 and result['total']>=len(result['entries'])
 checks['unknownStageRejected']=admin('/knowledge-entries?stage=RAW')[0]==400
 checks['unavailableEntryReturnsNoProse']=admin('/knowledge-entry?id='+str(uuid.uuid4()))==(404,None)
 status,spoofed=admin('/knowledge-entries?knowledge_base_id=untrusted-other');checks['clientKnowledgeBaseCannotExpandTrustedReadScope']=status==200 and {e['entry_id'] for e in spoofed['entries']}=={e['entry_id'] for e in result['entries'][:30]}
 details=[];evidenceChecked=set()
 for item in result['entries']:
  status,detail=admin('/knowledge-entry?id='+urllib.parse.quote(item['entry_id']));assert status==200;details.append(detail)
  assert detail['entry_id']==item['entry_id'] and detail['revision']>=item['revision'] and detail['sources']
  for source in detail['sources']:
   assert source['version_no']>0 and source['logical_name'] and source['facts']
   for fact in source['facts']:
    assert fact['fact_text'] and fact['evidence_ids']
    for evidenceId in fact['evidence_ids']:
     if evidenceId in evidenceChecked:continue
     code,evidence=admin('/evidence?id='+urllib.parse.quote(evidenceId));assert code==200 and evidence['raw_content'] and evidence['version_id']==source['version_id'];evidenceChecked.add(evidenceId)
 report['actualEntryDetails']=details
 checks['everyCurrentMemberFactLinksReadableSameVersionEvidence']=bool(details and evidenceChecked)
 assert all(re.fullmatch('[a-f0-9-]{36}',e) for e in evidenceChecked)
 sql="SELECT json_build_object('allCurrent',bool_and(e.active AND a.version_id IS NOT NULL),'actualEvidence',count(*))::text FROM evidence e LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE e.evidence_id IN ("+','.join("'"+e+"'" for e in sorted(evidenceChecked))+");"
 a.output.with_suffix('.readback.sql').write_text(sql)
 readback=subprocess.run(['docker','exec','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-At','-c',sql],capture_output=True,text=True,timeout=15);readback.check_returncode()
 database=json.loads(readback.stdout.strip());report['actualEvidenceReadback']=database
 checks['readonlyDatabaseConfirmsEveryLinkedEvidenceIsCurrentlyActive']=database['allCurrent'] and database['actualEvidence']==len(evidenceChecked)
 for stage in ['CONCEPT','WIKI']:
  code,filtered=admin('/knowledge-entries?stage='+stage);checks['nativeStageFilter-'+stage]=code==200 and all(e['stage']==stage for e in filtered['entries'])
 code,readerList=reader('/knowledge-entries');checks['regularMemberCanUseReadOnlyEntryBrowser']=code==200
 report['regularMemberEntries']=readerList
 for item in readerList['entries']:
  code,detail=reader('/knowledge-entry?id='+item['entry_id']);assert code==200 and detail['sources']
 checks['regularMemberVisibleEntriesHaveReadableCurrentDetail']=all(reader('/knowledge-entry?id='+e['entry_id'])[0]==200 for e in readerList['entries'])
 report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();save('PASS_ACTUAL_ENTRY_BROWSE' if all(checks.values()) else 'FAIL_ACTUAL_ENTRY_BROWSE')
except Exception as error:
 report['failure']=type(error).__name__+': '+str(error);save('FAIL_ACTUAL_ENTRY_BROWSE')
print(json.dumps({'status':report['status'],'passed':sum(checks.values()),'failed':[k for k,v in checks.items() if not v],'output':str(a.output)},ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
