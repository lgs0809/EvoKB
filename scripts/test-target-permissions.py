#!/usr/bin/env python3
"""Exercise current account/source authority with normal APIs and retain manual fixtures.

seed: create only marked test accounts/documents without replacing business data.
verify: inspect the actual browser query before and after revocation, including cached ZIPs.
All data/status/permission changes use application APIs. SQL is read-only inspection.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import importlib.util
_seed_spec=importlib.util.spec_from_file_location("target_seed",Path(__file__).with_name("seed-target-business-data.py"))
_seed_module=importlib.util.module_from_spec(_seed_spec)
_seed_spec.loader.exec_module(_seed_module)
multipart=_seed_module.multipart
ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:18481'
PRIVATE=ROOT/'.tmp/target/permission-accounts.json'
FILES=[('HT-ACL-031公开索引.md',10),('HT-ACL-031保密付款条款.md',30)]


def client(account):
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path, data=None, method=None):
        req=urllib.request.Request(BASE+'/api/enterprise'+path,data=None if data is None else json.dumps(data).encode(),method=method,headers={'Content-Type':'application/json'})
        try:
            with opener.open(req,timeout=180) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as failure:return failure.code,json.load(failure)
    status,body=request('/auth/login',account)
    if status!=200:raise ValueError('Normal login failed')
    return request,opener


def pg(sql):
    r=subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-XAt','-U','evokb_target','-d','evokb_target','-v','ON_ERROR_STOP=1'],input=sql,text=True,capture_output=True,check=True,timeout=30)
    return json.loads(r.stdout)


def save_private(value):
    if not PRIVATE.exists():
        fd=os.open(PRIVATE,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as f:json.dump(value,f,ensure_ascii=False,indent=2)
    else:PRIVATE.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');PRIVATE.chmod(0o600)


def seed():
    main=json.loads((ROOT/'.tmp/target/accounts.json').read_text())['admin']
    admin,_=client(main)
    accounts=json.loads(PRIVATE.read_text()) if PRIVATE.exists() else {}
    status,users=admin('/users')
    if status!=200:raise ValueError('Cannot inspect users')
    for key,role,level in [('admin','ADMIN',30),('confidential','USER',30),('public','USER',10)]:
        name='acceptance_permission_'+key
        matching=[u for u in users['users'] if u['username']==name]
        if key not in accounts:
            if matching:raise ValueError('Pre-existing test username without owned credentials; no reset performed')
            account={'username':name,'password':secrets.token_urlsafe(20),'role':role,'security_clearance':level,'display_name':'本地权限验收 '+key}
            status,body=admin('/users',account)
            if status!=201:raise ValueError('Account creation failed')
            accounts[key]={'username':name,'password':account['password'],'user_id':body['user']['user_id']}
            save_private(accounts)
        elif not matching:raise ValueError('Stored test account missing; do not silently replace it')
    manager,opener=client(accounts['admin'])
    docs=[]
    before=pg("SELECT COALESCE(json_agg(t),'[]') FROM (SELECT d.document_id,d.logical_name,v.version_id,v.sha256,v.lifecycle,m.security_level FROM document d JOIN document_version v ON v.document_id=d.document_id JOIN document_metadata m ON m.version_id=v.version_id) t;")
    for filename,level in FILES:
        path=ROOT/'deploy/acceptance/permission-fixtures'/filename
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        existing=[r for r in before if r['logical_name']==path.stem]
        if existing and (len(existing)!=1 or existing[0]['sha256']!=digest or existing[0]['lifecycle']!='ACTIVE'):
            raise ValueError('Fixture identity collision; existing business data preserved')
        content,content_type=multipart(path,dict(logical_name=path.stem,source_department='法务',document_type='权限验收合同',company='星桥制造',tags='本地权限验收,虚构数据'))
        req=urllib.request.Request(BASE+'/api/enterprise/upload',data=content,headers={'Content-Type':content_type})
        with opener.open(req,timeout=180) as response:result=json.load(response)
        status,_=manager('/document?version_id='+result['version_id'],dict(logical_name=path.stem,source_department='法务',material_category='合同',document_type='权限验收合同',manual_tags=['本地权限验收','虚构数据'],security_level=level),method='PATCH')
        if status!=200:raise ValueError('Normal classification API failed')
        docs.append(dict(filename=filename,documentId=result['document_id'],versionId=result['version_id'],sha256=digest,securityLevel=level,reused=result['reused']))
    return dict(status='PASS_NORMAL_API_PERMISSION_SEED',documents=docs,accounts={k:dict(userId=v['user_id'],username=v['username']) for k,v in accounts.items()},preservedBusinessRows=len(before))


def verify(session,output,related=None):
    accounts=json.loads(PRIVATE.read_text());manager,_=client(accounts['admin']);reader,opener=client(accounts['confidential']);public,_=client(accounts['public'])
    checks={}; observations=[]
    def check(name,value):checks[name]=bool(value)
    def current_permissions():
        return pg("SELECT COALESCE(json_agg(t),'[]') FROM (SELECT d.logical_name,v.version_id,m.security_level,m.acl_version FROM document d JOIN document_version v ON v.document_id=d.document_id JOIN document_metadata m ON m.version_id=v.version_id WHERE d.logical_name LIKE 'HT-ACL-031%') t;")
    status,_=manager('/user?id='+accounts['confidential']['user_id'],dict(security_clearance=30),method='PATCH')
    check('ownedTestAccountSetToAuthorizedBaselineViaApi',status==200)
    permissions=current_permissions()
    confidential=next(r for r in permissions if r['security_level']==30)
    secret_version=confidential['version_id']
    status,answer=reader('/query/session?id='+session)
    check('actualBrowserQueryAnswered',status==200 and answer['status']=='ANSWERED' and not answer['evidence_only'] and all(t in answer['answer'] for t in ['17','37','44.4']))
    if not checks['actualBrowserQueryAnswered']:raise ValueError('Browser must complete a real natural-language query before verification')
    citations=[c['evidence']['evidence_id'] for c in answer['citations']]
    check('answerContainsConfidentialOriginalCitation',any(c['evidence']['version_id']==secret_version for c in answer['citations']))
    status,published=reader('/package',dict(evidence_ids=citations));check('authorizedPackageCreated',status==200)
    download=urllib.parse.urljoin(BASE,published['download_url'])
    check('downloadUrlIsAuthenticatedApplicationUrl',download.startswith(BASE+'/api/enterprise/package/file?id=') and 'X-Amz' not in download)
    with opener.open(download,timeout=30) as response:
        content=response.read();check('actualAuthorizedZipBytesTransferred',len(content)==int(response.headers['Content-Length']))
    output.with_suffix('.zip').write_bytes(content)
    with zipfile.ZipFile(io.BytesIO(content)) as z:
        manifest=json.loads(z.read('manifest.json'))
        check('zipContainsActualConfidentialFixture',any(v['version_id']==secret_version and z.read(v['archive_path'])==(ROOT/'deploy/acceptance/permission-fixtures'/v['original_filename']).read_bytes() for v in manifest['versions']))
    status,record_package=reader('/query/history/record/package',dict(session_id=session,regenerate=False));check('normalHistoryPackageCreated',status==200 and record_package['status']=='AVAILABLE')
    package_urls=[download,urllib.parse.urljoin(BASE,record_package['download_url'])]
    group_id=None
    if related:
        status,group=reader('/query/groups',dict(name='本地权限验收组 '+session[:8],session_ids=[session,related]));check('actualUserQueriesGroupedViaApi',status==200)
        group_id=group['group_id']
        status,group_package=reader('/query/group/package',dict(group_id=group_id,regenerate=False));check('normalGroupPackageCreated',status==200 and group_package['status']=='AVAILABLE')
        package_urls.append(urllib.parse.urljoin(BASE,group_package['download_url']))
    # Changing a source classification revokes existing answers and cached packages,
    # independently of changing the account. Only these marked fixtures are changed.
    status,_=manager('/user?id='+accounts['admin']['user_id'],dict(security_clearance=40),method='PATCH')
    check('testManagerAuthorizedForClassificationRoundTrip',status==200)
    classification=dict(logical_name=confidential['logical_name'],source_department='法务',material_category='合同',document_type='权限验收合同',manual_tags=['本地权限验收','虚构数据'])
    try:
        status,_=manager('/document?version_id='+secret_version,dict(**classification,security_level=40),method='PATCH');check('sourceClassificationRaisedViaNormalApi',status==200)
        status,blocked=reader('/query/session?id='+session);check('sourceClassificationImmediatelyBlocksOldAnswer',status==200 and blocked.get('evidence_changed') and '权限或版本' in blocked['answer'] and not blocked['citations'])
        for index,url in enumerate(package_urls):
            try:
                with opener.open(url,timeout=30) as response:code=response.status
            except urllib.error.HTTPError as failure:code=failure.code
            check('sourceClassificationBlocksCachedPackage'+str(index),code==403)
    finally:
        status,_=manager('/document?version_id='+secret_version,dict(**classification,security_level=30),method='PATCH');check('fixtureClassificationRestoredViaNormalApi',status==200)
        status,_=manager('/user?id='+accounts['admin']['user_id'],dict(security_clearance=30),method='PATCH');check('testManagerClearanceRestored',status==200)
    status,recovered=reader('/query/session?id='+session);check('validSourcesRestoreOriginalAnswerWithoutModelOrDbRewrite',status==200 and recovered['answer']==answer['answer'] and not recovered['evidence_changed'])
    before=pg("SELECT row_to_json(t) FROM (SELECT final_answer,citation_evidence_ids FROM query_session WHERE session_id='"+session+"') t;")
    for api in [public,reader]:
        status,body=api('/documents?q=HT-ACL-031'); check('publicListHidesConfidential' if api==public else 'privilegedListShowsConfidential',status==200 and (not any(d['version_id']==secret_version for d in body['documents']) if api==public else any(d['version_id']==secret_version for d in body['documents'])))
    secret_evidence=next(c['evidence']['evidence_id'] for c in answer['citations'] if c['evidence']['version_id']==secret_version)
    for path in ['/document/detail?version_id='+secret_version,'/evidence?id='+secret_evidence,'/evidence/source?id='+secret_evidence,'/evidence/source/file?id='+secret_evidence]:
        req=urllib.request.Request(BASE+'/api/enterprise'+path)
        pub_client,pub_opener=client(accounts['public'])
        try:
            with pub_opener.open(req,timeout=30) as response:status=response.status
        except urllib.error.HTTPError as failure:status=failure.code
        check('publicDeniedDirect'+path.split('?')[0],status in [400,403])
    status,_=public('/user?id='+accounts['public']['user_id'],dict(security_clearance=127),method='PATCH');check('readerCannotGrantSelfClearance',status==403)
    status,found=public('/search',dict(query='HT-ACL-031 17个工作日 37% 尾款',filters=dict(source_department='法务',security_clearance=127),limit=8))
    check('forgedClientClearanceDoesNotReachConfidentialEvidence',status==200 and all(e['version_id']!=secret_version for r in found['results'] for e in r['evidence']))
    status,_=manager('/user?id='+accounts['confidential']['user_id'],dict(security_clearance=10),method='PATCH');check('normalAdminApiRevokedClearance',status==200)
    status,me=reader('/auth/me');check('existingCookieUsesCurrentAuthority',status==200 and me['user']['security_clearance']==10)
    status,revoked=reader('/query/session?id='+session);check('oldAnswerAndCitationsSuppressed',status==200 and '权限或版本' in revoked['answer'] and not revoked['citations'] and not any(t in revoked['answer'] for t in ['17','37','44.4']))
    status,sessions=reader('/query/sessions');check('sessionListDoesNotLeakOldAnswer',status==200 and all(not any(t in q['answer'] for t in ['17','37','44.4']) for q in sessions['sessions'] if q['session_id']==session))
    status,history=reader('/query/history');check('historyDoesNotLeakOldAnswer',status==200 and '44.4' not in json.dumps(history,ensure_ascii=False))
    status,_=reader('/query/history/record/package',dict(session_id=session,regenerate=False));check('existingHistoryPackageCannotBypassRevocation',status==403)
    if group_id:
        status,groups=reader('/query/groups');check('groupHistoryDoesNotLeakOldAnswerOrDownloadUrl',status==200 and '44.4' not in json.dumps(groups,ensure_ascii=False) and all(r['latest_package']['status']=='ACCESS_CHANGED' for g in groups['groups'] for r in g['records'] if r['session_id']==session and r.get('latest_package')))
        status,_=reader('/query/group/package',dict(group_id=group_id,regenerate=False));check('existingGroupPackageCannotBypassRevocation',status==403)
    def cached_download(_):
        _,op=client(accounts['confidential'])
        try:
            with op.open(download,timeout=30) as response:return response.status
        except urllib.error.HTTPError as failure:return failure.code
    with ThreadPoolExecutor(max_workers=6) as pool:codes=list(pool.map(cached_download,range(12)))
    check('twelveConcurrentCachedDownloadsDeniedAfterRevocation',codes==[403]*12)
    status,_=reader('/package',dict(evidence_ids=citations));check('newPackageDeniedAfterRevocation',status==403)
    after=pg("SELECT row_to_json(t) FROM (SELECT final_answer,citation_evidence_ids FROM query_session WHERE session_id='"+session+"') t;")
    check('originalPersistedAnswerUnmodified',before==after)
    final_permissions=current_permissions()
    check('sourceClassificationsRestoredAndAclRevisionIncreased',[(r['logical_name'],r['security_level']) for r in permissions]==[(r['logical_name'],r['security_level']) for r in final_permissions] and next(r['acl_version'] for r in final_permissions if r['version_id']==secret_version)==confidential['acl_version']+2)
    observations.append(dict(sessionId=session,packageId=published['package_id'],zipSha256=hashlib.sha256(content).hexdigest(),cachedDownloadStatuses=codes,sourcePermissions=final_permissions,groupId=group_id,originalAnswerSha256=hashlib.sha256(before['final_answer'].encode()).hexdigest()))
    return dict(status='PASS_TARGET_CURRENT_AUTHORITY_AND_REVOCATION' if all(checks.values()) else 'FAIL_TARGET_CURRENT_AUTHORITY_AND_REVOCATION',checks=checks,observations=observations,boundary='Actual PostgreSQL, Milvus, Qwen, MinIO, browser query and cached ZIP. Permission changes via admin API; source/task success states never manufactured. Does not certify MinerU, Stream workers, selective evolution or complete Wiki build dependency catalog.')


def reader_clearance(level):
    """Manual test convenience, restricted to the owned synthetic reader account."""
    accounts=json.loads(PRIVATE.read_text());manager,_=client(accounts['admin'])
    code,body=manager('/users')
    account=accounts['confidential']
    matching=[u for u in body.get('users',[]) if u.get('user_id')==account['user_id'] and u.get('username')=='acceptance_permission_confidential' and u.get('role')=='USER']
    if code!=200 or len(matching)!=1:raise ValueError('Owned synthetic reader identity mismatch; nothing changed')
    code,changed=manager('/user?id='+account['user_id'],dict(security_clearance=level),method='PATCH')
    if code!=200 or changed['user']['security_clearance']!=level:raise ValueError('Normal admin permission API did not apply the test clearance')
    return dict(status='PASS_SYNTHETIC_READER_CLEARANCE_UPDATED',username=account['username'],userId=account['user_id'],securityClearance=level,boundary='Only the marked synthetic reader is changed through the authenticated admin API. Documents, task states and other users are preserved.')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=['seed','verify','reader-grant','reader-revoke']);p.add_argument('--session');p.add_argument('--related-session');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise ValueError('Keep previous attempts; choose a fresh report')
    if a.phase=='verify':
        import re
        if not a.session or not re.fullmatch(r'[0-9a-f-]{36}',a.session):raise ValueError('A real query UUID is required')
    if a.related_session and not re.fullmatch(r'[0-9a-f-]{36}',a.related_session):raise ValueError('A related real query UUID is required')
    result=seed() if a.phase=='seed' else reader_clearance(30 if a.phase=='reader-grant' else 10) if a.phase in ['reader-grant','reader-revoke'] else verify(a.session,a.output,a.related_session)
    result.update(actualOperationTime=datetime.now(timezone.utc).isoformat(),jarSha256=json.loads((ROOT/'.tmp/target/process.json').read_text())['jarSha256'])
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k in ['status','checks','documents']},ensure_ascii=False))
    if result['status'].startswith('FAIL'):raise SystemExit(1)
if __name__=='__main__':main()
