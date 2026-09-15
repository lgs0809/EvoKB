#!/usr/bin/env python3
"""Exercise actual authenticated Streamable HTTP MCP and scoped admin APIs.

Only marked synthetic test accounts are changed, through normal admin APIs.
No source/workflow state is manufactured. Reports omit cookies and passwords.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:18481'
spec=importlib.util.spec_from_file_location('permissions',Path(__file__).with_name('test-target-permissions.py'))
permissions=importlib.util.module_from_spec(spec);spec.loader.exec_module(permissions)

class Mcp:
    def __init__(self,opener):self.opener=opener;self.session=None;self.counter=0
    def call(self,method,params=None,notification=False,headers=None):
        self.counter+=1
        payload=dict(jsonrpc='2.0',method=method)
        if not notification:payload['id']=self.counter
        if params is not None:payload['params']=params
        actual={'Content-Type':'application/json','Accept':'application/json, text/event-stream','MCP-Protocol-Version':'2025-06-18'}
        if self.session:actual['Mcp-Session-Id']=self.session
        actual.update(headers or {})
        request=urllib.request.Request(BASE+'/mcp',data=json.dumps(payload).encode(),headers=actual)
        try:
            with self.opener.open(request,timeout=180) as response:
                if response.headers.get('Mcp-Session-Id'):self.session=response.headers['Mcp-Session-Id']
                if response.status==202:return response.status,{}
                if 'text/event-stream' not in response.headers.get('Content-Type',''):return response.status,json.load(response)
                # Read the actual JSON-RPC response; close the completed request stream.
                for line in response:
                    if line.startswith(b'data:'):
                        value=json.loads(line[5:].strip())
                        if value.get('id')==payload.get('id'):return response.status,value
                raise RuntimeError('MCP request stream ended without its response')
        except urllib.error.HTTPError as failure:
            return failure.code,dict(http_error=True)
    def initialize(self):
        code,value=self.call('initialize',dict(protocolVersion='2025-06-18',capabilities={},clientInfo=dict(name='evokb-local-access-acceptance',version='1')))
        if code!=200 or 'result' not in value or not self.session:raise RuntimeError('Authenticated MCP initialization failed')
        self.call('notifications/initialized',notification=True)
        return value['result']
    def tool(self,name,arguments,meta=None):
        params=dict(name=name,arguments=arguments)
        if meta:params['_meta']=meta
        return self.call('tools/call',params)
    def delete(self,headers=None):
        actual={'Accept':'application/json, text/event-stream','Mcp-Session-Id':self.session};actual.update(headers or {})
        try:
            with self.opener.open(urllib.request.Request(BASE+'/mcp',method='DELETE',headers=actual),timeout=20) as r:return r.status
        except urllib.error.HTTPError as failure:return failure.code

def tool_data(value):
    if 'error' in value:raise RuntimeError('MCP protocol/tool error')
    result=value['result']
    if result.get('isError'):raise RuntimeError('MCP tool returned an error')
    if result.get('structuredContent'):return result['structuredContent']
    texts=[c['text'] for c in result['content'] if c.get('type')=='text']
    if len(texts)!=1:raise RuntimeError('Unexpected MCP tool content')
    return json.loads(texts[0])

def run(output):
    accounts=json.loads((ROOT/'.tmp/target/permission-accounts.json').read_text())
    original=json.loads((ROOT/'.tmp/target/accounts.json').read_text())
    manager,_=permissions.client(accounts['admin'])
    reader,reader_opener=permissions.client(accounts['confidential'])
    public,public_opener=permissions.client(accounts['public'])
    low_admin,low_opener=permissions.client(original['admin'])
    checks={};observations=[];clients=[]
    def check(name,value):
        checks[name]=bool(value)
        if not value:raise AssertionError(name)
    fixtures=permissions.pg("SELECT json_agg(t) FROM (SELECT v.version_id,m.security_level FROM document_version v JOIN document d ON d.document_id=v.document_id JOIN document_metadata m ON m.version_id=v.version_id WHERE d.logical_name LIKE 'HT-ACL-031%' AND v.lifecycle='ACTIVE')t;")
    secret=next(r['version_id'] for r in fixtures if r['security_level']==30)
    public_version=next(r['version_id'] for r in fixtures if r['security_level']==10)
    evidence=permissions.pg("SELECT json_agg(evidence_id) FROM evidence WHERE version_id='"+secret+"' AND active=TRUE;")[0]
    args=dict(query='星桥 HT-ACL-031 采购合同尾款支付期限、比例和金额',sourceDepartment=None,materialCategory=None,documentType=None,limit=10)
    try:
        check('ownedSyntheticAccountBaselineViaNormalApi',manager('/user?id='+accounts['confidential']['user_id'],dict(security_clearance=30),method='PATCH')[0]==200)
        anonymous=Mcp(urllib.request.build_opener())
        check('anonymousMcpInitializationDenied',anonymous.call('initialize',dict(protocolVersion='2025-06-18',capabilities={},clientInfo=dict(name='anon',version='1')))[0]==401)
        high=Mcp(reader_opener);high.initialize();clients.append(high)
        low=Mcp(public_opener);low.initialize();clients.append(low)
        code,value=high.call('tools/list');tools=value.get('result',{}).get('tools',[])
        check('frameworkToolSchemaExcludesTrustedIdentity',code==200 and {t['name'] for t in tools}=={'search_knowledge','package_sources'} and all(not any(k in json.dumps(t['inputSchema']) for k in ['ToolContext','userId','securityClearance','security_clearance']) for t in tools))
        code,value=high.tool('search_knowledge',args);results=tool_data(value)['results']
        check('trustedMcpReadsActualConfidentialEvidence',code==200 and any(e['versionId']==secret for r in results for e in r['evidence']))
        code,value=low.tool('search_knowledge',args,dict(user_id=accounts['confidential']['user_id'],security_clearance=127));results=tool_data(value)['results']
        check('forgedMcpMetadataCannotGrantPrivateAccess',code==200 and all(e['versionId']!=secret for r in results for e in r['evidence']))
        code,value=high.tool('package_sources',dict(evidenceIds=[evidence]));package=tool_data(value)
        url=urllib.parse.urljoin(BASE,package['downloadUrl'])
        check('mcpPackageUsesAuthenticatedApplicationDownload',url.startswith(BASE+'/api/enterprise/package/file?id=') and 'X-Amz' not in url)
        with reader_opener.open(url,timeout=30) as r:content=r.read();check('actualMcpZipTransferCompleted',len(content)==int(r.headers['Content-Length']))
        output.with_suffix('.zip').write_bytes(content)
        with zipfile.ZipFile(io.BytesIO(content)) as z:
            manifest=json.loads(z.read('manifest.json'))
            check('mcpZipContainsExactOriginalFixtureBytes',all(z.read(v['archive_path'])==(ROOT/'deploy/acceptance/permission-fixtures'/v['original_filename']).read_bytes() for v in manifest['versions']) and any(v['version_id']==secret for v in manifest['versions']))
        source_url=BASE+'/api/enterprise/evidence/source/file?id='+evidence
        with reader_opener.open(source_url,timeout=30) as response:
            source_bytes=response.read()
            check('originalSourceServedAsNonExecutableAttachment',response.headers.get('Content-Type')=='application/octet-stream' and response.headers.get('Content-Disposition','').startswith('attachment;') and response.headers.get('X-Content-Type-Options')=='nosniff' and response.headers.get('Content-Security-Policy')=="sandbox; default-src 'none'")
            check('originalAttachmentContainsExactFixtureBytes',source_bytes==(ROOT/'deploy/acceptance/permission-fixtures/HT-ACL-031保密付款条款.md').read_bytes())
        check('foreignAccountCannotReuseMcpSessionForCall',low.call('tools/list',headers={'Mcp-Session-Id':high.session})[0]==404)
        check('foreignAccountCannotDeleteMcpSession',low.delete({'Mcp-Session-Id':high.session})==404)
        check('mcpCrossOriginRequestDenied',high.call('tools/list',headers={'Origin':'https://untrusted.example'})[0]==403)
        for path,body in [('/compilation/summary',dict(version_id=secret)),('/compilation/graph',dict(summary_id='unknown-source')),('/compilation/wiki',dict(wiki_type='CONTRACT',title='Synthetic private input',canonical_subject_id='',summary_ids=['unknown-source'])),('/compilation/wiki-keywords',dict(wiki_id='unknown-source',wiki_version=1))]:
            check('lowClearanceAdminDenied'+path.replace('/','_'),low_admin(path,body)[0]==403)
        golden=json.dumps(dict(id='scoped-permission-'+uuid.uuid4().hex,version_ids=[secret],expected_summary_facts=[],expected_relations=[],expected_entity_mappings=[],expected_wiki_claims=[])).encode()
        boundary='evokb-access-'+uuid.uuid4().hex
        data=(('--'+boundary+'\r\nContent-Disposition: form-data; name="cases"; filename="scope.jsonl"\r\nContent-Type: application/x-ndjson\r\n\r\n').encode()+golden+b'\r\n'+('--'+boundary+'--\r\n').encode())
        request=urllib.request.Request(BASE+'/api/enterprise/evaluation/artifacts',data=data,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
        try:
            with low_opener.open(request,timeout=30) as r:code=r.status
        except urllib.error.HTTPError as failure:code=failure.code
        check('lowClearanceAdminCannotEvaluatePrivateVersion',code==403)
        for endpoint in ['/quality','/tasks','/audit']:
            code,view=low_admin(endpoint);check('scopedAdminReadHealthy'+endpoint,code==200 and secret not in json.dumps(view))
        # Restore a lower clearance while keeping the same authenticated MCP protocol session.
        check('revocationAppliedViaNormalApi',manager('/user?id='+accounts['confidential']['user_id'],dict(security_clearance=10),method='PATCH')[0]==200)
        code,value=high.tool('search_knowledge',args);results=tool_data(value)['results']
        check('existingMcpSessionUsesCurrentRevokedAuthority',code==200 and all(e['versionId']!=secret for r in results for e in r['evidence']))
        code,value=high.tool('package_sources',dict(evidenceIds=[evidence]))
        check('existingMcpSessionCannotPackageRevokedSource',code==200 and (value.get('result',{}).get('isError') or 'error' in value))
        try:
            with reader_opener.open(url,timeout=30) as r:code=r.status
        except urllib.error.HTTPError as failure:code=failure.code
        check('oldMcpZipUrlCannotBypassRevocation',code==403)
        replay=urllib.request.Request(BASE+'/mcp',headers={'Accept':'text/event-stream','Mcp-Session-Id':high.session,'Last-Event-ID':'prior-private-response'})
        try:
            with reader_opener.open(replay,timeout=20) as r:code=r.status
        except urllib.error.HTTPError as failure:code=failure.code
        check('cachedSseReplayCannotBypassSourceRevocation',code==405)

        code,groups=reader('/query/groups')
        check('historyGroupCarriesCurrentAccessChangeForUi',code==200 and any(r.get('evidence_changed') for g in groups['groups'] for r in g['records']))
        bindings=permissions.pg("SELECT json_agg(t) FROM (SELECT session_id,user_id,expires_at FROM mcp_session_access WHERE user_id='"+accounts['confidential']['user_id']+"')t;")
        check('mcpOwnershipPersistedInActualPostgres',any(r['session_id']==high.session and r['user_id']==accounts['confidential']['user_id'] for r in bindings))
        observations.append(dict(secretVersion=secret,publicVersion=public_version,zipSha256=hashlib.sha256(content).hexdigest(),protocolSessions=len(clients),modelContextIdentity='Authenticated transport only'))
        status='PASS_REAL_MCP_CURRENT_AUTHORITY'
    except Exception as failure:
        status='FAIL_REAL_MCP_CURRENT_AUTHORITY';observations.append(dict(failureType=type(failure).__name__,failure=str(failure)))
    finally:
        manager('/user?id='+accounts['confidential']['user_id'],dict(security_clearance=10),method='PATCH')
        for client in clients:
            check('ownedMcpSessionClosed'+str(clients.index(client)),client.delete() in [200,204])
    result=dict(status=status,checks=checks,observations=observations,actualOperationTime=datetime.now(timezone.utc).isoformat(),jarSha256=json.loads((ROOT/'.tmp/target/process.json').read_text())['jarSha256'],boundary='Actual authenticated MCP transport, PG/Milvus/Qwen retrieval and MinIO original ZIP. No mock transport or synthetic task success. Positive Summary/Graph/Wiki generation and formal evaluation remain separate unfinished work.')
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False))
    if status.startswith('FAIL'):raise SystemExit(1)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    if args.output.exists():raise ValueError('Keep previous attempts; use a fresh report')
    run(args.output)
