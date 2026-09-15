#!/usr/bin/env python3
"""Exercise real local concurrent login and validate each returned session.

Retains failures without recording passwords or session tokens.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.cookiejar
import json
from pathlib import Path
import threading
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[1]


def run(output, environment='legacy'):
    if output.exists():raise ValueError('Choose a new report path')
    private=ROOT/('.tmp/target' if environment=='target' else '.tmp/acceptance')
    base='http://127.0.0.1:'+('18481' if environment=='target' else '18480')+'/api/enterprise'
    account=json.loads((private/'accounts.json').read_text())['admin']
    responses=[]
    for iteration in range(3):
        barrier=threading.Barrier(3)
        def login(index):
            opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            request=urllib.request.Request(base+'/auth/login',data=json.dumps(account).encode(),headers={'Content-Type':'application/json'})
            barrier.wait()
            try:
                with opener.open(request,timeout=30) as response:
                    user=json.load(response)['user'];status=response.status
                with opener.open(base+'/auth/me',timeout=30) as response:me=json.load(response)['user']
                return {'iteration':iteration,'request':index,'status':status,
                        'correctIdentity':user['user_id']==me['user_id']==account['user_id'],
                        'currentRolePreserved':user['role']==me['role']==account['role']}
            except urllib.error.HTTPError as error:return {'iteration':iteration,'request':index,'status':error.code}
        with ThreadPoolExecutor(max_workers=3) as pool:responses.extend(pool.map(login,range(3)))
    state=json.loads((private/'process.json').read_text())
    passed=all(r['status']==200 and r.get('correctIdentity') and r.get('currentRolePreserved') for r in responses)
    result={'status':('PASS' if passed else 'FAIL')+'_REAL_'+('POSTGRES' if environment=='target' else 'MYSQL')+'_CONCURRENT_LOGIN',
            'environment':environment,
            'jarSha256':state['jarSha256'],'actualOperationTime':datetime.now(timezone.utc).isoformat(),
            'requests':responses,'boundary':'Nine real logins with up to three simultaneous requests; no password or role change.'}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':result['status'],'requests':len(responses)}))
    if not passed:raise SystemExit(1)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--environment',choices=['legacy','target'],default='legacy')
    args=p.parse_args();run(args.output,args.environment)
