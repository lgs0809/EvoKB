"""Use the existing authenticated durable Concept projection command; never edit task state."""
import argparse
from datetime import datetime, timezone
import http.cookiejar
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--summary',required=True)
p.add_argument('--jar-sha256',required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args();uuid.UUID(a.summary)
if a.output.exists():raise ValueError('Use a fresh evidence output')
state=json.loads((ROOT/'.tmp/target/process.json').read_text())
if state['jarSha256']!=a.jar_sha256:raise ValueError('Bind the actual deployed immutable JAR')
accounts=json.loads((ROOT/'.tmp/target/accounts.json').read_text())
calls=[];checks={};report=dict(startedAt=datetime.now(timezone.utc).isoformat(),process=state,summaryId=a.summary)

def client(account):
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path,body=None):
        req=urllib.request.Request(state['url']+'/api/enterprise'+path,
            data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        try:
            with opener.open(req,timeout=20) as response:code=response.status;data=response.read()
        except urllib.error.HTTPError as error:code=error.code;data=error.read()
        calls.append(dict(path=path,status=code,at=datetime.now(timezone.utc).isoformat()))
        return code,json.loads(data) if data else None
    code,data=request('/auth/login',account)
    if code!=200:raise ValueError('Actual target login failed')
    return request

try:
    admin=client(accounts['admin']);reader=client(accounts['reader'])
    code,listed=admin('/knowledge-entries?page_size=100');assert code==200
    before=[]
    for entry in listed['entries']:
        code,detail=admin('/knowledge-entry?id='+urllib.parse.quote(entry['entry_id']));assert code==200
        if any(source['summary_id']==a.summary for source in detail['sources']):before.append(detail)
    report['actualEntriesBefore']=before
    checks['realCurrentEntriesHaveThisFrozenSummary']=bool(before)
    code,_=reader('/compilation/concept-projection',dict(summary_id=a.summary))
    checks['ordinaryMemberCannotSubmitAdminRebuild']=code==403
    code,first=admin('/compilation/concept-projection',dict(summary_id=a.summary))
    checks['normalDurableCommandAccepted']=code==202 and bool(first.get('task_id'))
    report['acceptedTask']=first
    code,again=admin('/compilation/concept-projection',dict(summary_id=a.summary))
    checks['repeatNormalSubmissionReusesSameTask']=code==202 and first['task_id']==again['task_id']
    report['repeatAcceptedTask']=again
    report['status']='PASS_NORMAL_DURABLE_COMMAND_ADMISSION' if all(checks.values()) else 'FAIL_NORMAL_DURABLE_COMMAND_ADMISSION'
except Exception as error:report.update(status='FAIL_NORMAL_DURABLE_COMMAND_ADMISSION',failure=type(error).__name__+': '+str(error))
report.update(checks=checks,calls=calls,finishedAt=datetime.now(timezone.utc).isoformat(),
    boundary='Normal authenticated admin command on existing audited Summary, plus ordinary-user rejection and idempotent submission. Admission is not generation/publication success; inspect the returned task and all physical outputs independently. No SQL writes, attempt resets, new source input or model replacement.')
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(status=report['status'],passed=sum(checks.values()),failed=[key for key,value in checks.items() if not value],taskId=report.get('acceptedTask',{}).get('task_id'))))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
