#!/usr/bin/env python3
"""Verify active-version reuse and local role boundaries through normal APIs.

Uploads only existing marked acceptance fixtures. Does not reset data, change
permissions, bypass approvals, or fabricate generation/knowledge success.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:18480/api/enterprise'


def client(account=None):
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path,data=None,content_type='application/json'):
        raw=None if data is None else data if isinstance(data,bytes) else json.dumps(data).encode()
        req=urllib.request.Request(BASE+path,data=raw,headers={'Content-Type':content_type})
        try:
            with opener.open(req,timeout=30) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as error:
            return error.code,json.load(error)
    if account:
        status,user=request('/auth/login',account)
        if status!=200 or user['user']['username']!=account['username']:raise ValueError('Normal identity login failed')
    return request


def multipart(filename,logical_name):
    boundary='evokb-acceptance-'+secrets.token_hex(16)
    data=(ROOT/'deploy/acceptance/data'/filename).read_bytes()
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="logical_name"\r\n\r\n{logical_name}\r\n'
          f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/markdown\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
    return body,'multipart/form-data; boundary='+boundary


def run(args):
    if args.output.exists():raise ValueError('Use a fresh output path')
    baseline=json.loads(args.baseline.read_text()); accounts=json.loads((ROOT/'.tmp/acceptance/accounts.json').read_text())
    checks={};responses=[]
    def check(name,passed,detail=None):
        checks[name]=bool(passed)
        if detail is not None:responses.append({'check':name,'detail':detail})
    anon=client();reader=client(accounts['reader'])
    status,body=anon('/documents');check('anonymous_cannot_read_documents',status==401,{'status':status})
    for endpoint in ['/users','/model-configurations','/quality']:
        status,body=reader(endpoint);check('reader_denied_'+endpoint.split('/')[-1],status==403,{'status':status})
    status,access=reader('/access');check('reader_upload_disabled',status==200 and access.get('can_upload') is False)
    session=next(s for s in baseline['sessions'] if s['userId']==accounts['admin']['user_id'])
    status,body=reader('/query/session?id='+session['sessionId']);check('reader_cannot_read_admin_session',status==400 and body.get('error')=='query session not found',{'status':status})
    batch=baseline['batches'][0]
    status,body=reader('/batch?id='+batch['batchId']);check('reader_cannot_read_admin_upload_batch',status==403,{'status':status})
    for version in baseline['versions']:
        if version['versionNo']!=1 or version['lifecycle']!='ACTIVE':continue
        body,ctype=multipart(version['filename'],version['logicalName'])
        status,data=reader('/upload',body,ctype);check('reader_cannot_upload_'+version['filename'],status==403,{'status':status})
    versions=[v for v in baseline['versions'] if v['lifecycle']=='ACTIVE']
    def duplicate(version):
        req=client(accounts['admin']);body,ctype=multipart(version['filename'],version['logicalName'])
        status,response=req('/upload',body,ctype)
        return version,status,response
    # Two overlapping requests for each already active fixture exercise concurrent idempotent reuse.
    with ThreadPoolExecutor(max_workers=3) as pool:
        for index,(version,status,response) in enumerate(pool.map(duplicate,versions*2)):
            check('concurrent_active_version_reuse_'+str(index),status==201 and response.get('reused') is True
                  and response.get('version_id')==version['versionId'] and response.get('version_no')==version['versionNo'],
                  {'status':status,'versionId':response.get('version_id'),'reused':response.get('reused')})
    result={'status':'PASS_NORMAL_API_REUSE_AND_ROLE_BOUNDARIES' if all(checks.values()) else 'FAIL_NORMAL_API_REUSE_AND_ROLE_BOUNDARIES',
            'actualOperationTime':datetime.now(timezone.utc).isoformat(),'checks':checks,'observations':responses,
            'baselineSha256':hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
            'boundary':'ADMIN/USER and session/batch ownership only. Does not assert department/restricted-document ACL or new-content concurrency.'}
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':result['status'],'passed':sum(checks.values()),'failed':[k for k,v in checks.items() if not v]},ensure_ascii=False))
    if not all(checks.values()):raise SystemExit(1)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    run(p.parse_args())
