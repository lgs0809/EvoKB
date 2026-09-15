#!/usr/bin/env python3
"""Read actual normal stable Graph/Wiki tasks and physical outputs without mutations or inference."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('actual_generation_binding', ROOT/'scripts/test-target-stable-entry-publication.py')
binding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(binding)


def graph_rows(rows):
    """Normalize the same native/public view, preserving endpoints, scope result and all proofs."""
    keys = dict(relation_id='relationId', source_entity_id='sourceEntityId', source_name='sourceName',
                source_type='sourceType', target_entity_id='targetEntityId', target_name='targetName',
                target_type='targetType', relation_type='relationType', valid_from='validFrom',
                valid_to='validTo', conflict_status='conflictStatus', evidence_ids='evidenceIds', score='score')
    result = {}
    for row in rows:
        item = {key:row.get(key,row.get(native)) for key,native in keys.items()}
        for key in ['valid_from','valid_to']:
            if item[key]: item[key] = datetime.fromisoformat(item[key].replace('Z','+00:00')).isoformat()
        item['evidence_ids'] = sorted(item['evidence_ids'])
        if item['relation_id'] in result: raise ValueError('Duplicate actual Graph relation identity')
        result[item['relation_id']] = item
    return result


def client(account=None):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path, data=None):
        req = urllib.request.Request('http://127.0.0.1:18481/api/enterprise'+path,
            data=None if data is None else json.dumps(data).encode(), headers={'Content-Type':'application/json'})
        try:
            with opener.open(req, timeout=30) as response: return response.status,json.load(response)
        except urllib.error.HTTPError as failure: return failure.code,json.load(failure)
    if account:
        code,body=request('/auth/login',{'username':account['username'],'password':account['password']})
        if code!=200 or body['user']['user_id']!=account['user_id']: raise ValueError('Normal existing acceptance account login failed')
    return request


def keys(value):
    if isinstance(value,dict): return set(value).union(*(keys(v) for v in value.values()))
    if isinstance(value,list): return set().union(*(keys(v) for v in value))
    return set()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',required=True,help='Actual selected task source version, read from the normal task record')
    parser.add_argument('--task',required=True,help='Actual normal Graph/Wiki task UUID; no historical-success substitution')
    parser.add_argument('--kind',required=True,choices=['GRAPH','WIKI'])
    parser.add_argument('--query',help='Actual normal Graph page browse query; required for Graph')
    parser.add_argument('--execution-proof',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();uuid.UUID(args.version);uuid.UUID(args.task)
    if args.kind=='GRAPH' and not (args.query or '').strip(): raise ValueError('Record the actual normal Graph browse query')
    artifacts=[args.output,args.output.with_suffix('.native.json'),args.output.with_suffix('.native.java'),
               args.output.with_suffix('.compile.log'),args.output.with_suffix('.runtime.log')]
    if any(p.exists() for p in artifacts): raise ValueError('Use a fresh output stem and preserve every earlier result')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    state=json.loads((ROOT/'.tmp/target/process.json').read_text());artifact=Path(state['artifact'])
    if hashlib.sha256(artifact.read_bytes()).hexdigest()!=state['jarSha256']: raise ValueError('Immutable deployed reader artifact changed')
    prior=json.loads(args.execution_proof.read_text())
    common=dict(actualOperationTime=datetime.now(timezone.utc).isoformat(),selectedActualTaskId=args.task,
                sourceVersion=args.version,kind=args.kind,readerJarSha256=state['jarSha256'],
                executionProofSha256=hashlib.sha256(args.execution_proof.read_bytes()).hexdigest(),
                completeObservedTaskHistory=prior['database']['tasks'],
                completeObservedEntryHistory=prior['database'].get('entries',[]))
    try:
        selected=binding.select_observed_execution(prior,args.version,args.task,state,args.kind,'entry/')
    except ValueError as failure:
        report=dict(common,status='FAIL_ACTUAL_DERIVED_EXECUTION_BINDING',failure=str(failure),
                    boundary='No native reader/API/model run. The specified new task failed validation; no historic success substitutes.')
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(report['status']);raise SystemExit(1)
    settings=dict(line.split('=',1) for line in (ROOT/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
    accounts=json.loads((ROOT/'.tmp/target/accounts.json').read_text())
    java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin'
    source=ROOT/'scripts/StableDerivedReadbackProbe.java';source_bytes=source.read_bytes()
    args.output.with_suffix('.native.java').write_bytes(source_bytes)
    with tempfile.TemporaryDirectory(prefix='evokb-stable-derived-readback-') as temp:
        work=Path(temp);frozen=work/source.name;frozen.write_bytes(source_bytes)
        with zipfile.ZipFile(artifact) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'): (work/Path(name).name).write_bytes(archive.read(name))
                elif name.startswith('BOOT-INF/classes/') and name.endswith('.class'):
                    path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))
        cp=os.pathsep.join((str(work/'*'),str(work),str(work/'BOOT-INF/classes')))
        compiled=subprocess.run([str(java/'javac'),'-J-Xmx128m','-J-XX:ActiveProcessorCount=2','-cp',cp,'-d',str(work),str(frozen)],capture_output=True,text=True,timeout=60)
        args.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr);compiled.check_returncode()
        env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL=settings['EKB_JDBC_URL'],
                 PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'],
                 PROBE_KNOWLEDGE_BASE=settings.get('EKB_KNOWLEDGE_BASE_ID','enterprise'))
        native_path=args.output.with_suffix('.native.json')
        result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-cp',cp,
            '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','StableDerivedReadbackProbe',
            str(native_path.resolve()),args.task,args.version,args.kind,accounts['admin']['user_id'],args.query or '',accounts['reader']['user_id']],
            env=env,capture_output=True,timeout=180)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout+result.stderr)
    native=json.loads(native_path.read_text())
    checks=dict(native.get('checks',{}));checks['nativeSelectedActualTaskReadbackPassed']=result.returncode==0 and native['status']=='PASS_ACTUAL_STABLE_DERIVED_PUBLICATION'
    report=dict(common,checks=checks,native=native,observedExecution=selected,
                actualGenerationJarSha256=prior['process']['jarSha256'],probeSourceSha256=hashlib.sha256(source_bytes).hexdigest(),
                bindingSourceSha256=hashlib.sha256((ROOT/'scripts/test-target-stable-entry-publication.py').read_bytes()).hexdigest())
    if checks['nativeSelectedActualTaskReadbackPassed']:
        admin=reader=None
        try:
            admin=client(accounts['admin']);reader=client(accounts['reader']);anonymous=client()
            if args.kind=='GRAPH':
                path='/graph?'+urllib.parse.urlencode(dict(q=args.query,depth=2,limit=100))
                code,body=admin(path);report['normalGraphApi']=body
                checks['normalAuthorizedGraphApiMatchesNativePublishedView']=code==200 and graph_rows(body.get('relations',[]))==graph_rows(native['authorizedNativeGraphBrowse'])
            else:
                wiki=native['actualWiki'];path='/wiki?'+urllib.parse.urlencode(dict(id=wiki['wiki_id'],version=wiki['wiki_version']))
                code,body=admin(path);report['normalWikiApi']=body
                api_claims={c['claim_id']:c['claim_text'] for c in body.get('claims',[])}
                native_claims={c['claimId']:c['claimText'] for c in native['actualWikiClaimsNativeAuditReadback']}
                checks['normalAuthorizedWikiApiMatchesActualCurrentClaims']=code==200 and body.get('wiki_id')==wiki['wiki_id'] and body.get('wiki_version')==wiki['wiki_version'] and body.get('lifecycle')=='ACTIVE' and api_claims==native_claims
            reader_code,reader_body=reader(path);anonymous_code,_=anonymous(path)
            checks['anonymousCannotReadActualOutput']=anonymous_code==401
            if args.kind=='GRAPH':
                checks['normalReaderGraphApiMatchesItsOwnAuthority']=reader_code==200 and graph_rows(reader_body.get('relations',[]))==graph_rows(native['authorizedReaderNativeGraphBrowse'])
            else:
                allowed=native['readerSecurityClearance']>=native['fullInputRequiredClearance']
                checks['normalReaderWikiRespectsFullInputClearance']=reader_code==200 if allowed else reader_code in {403,404}
            checks['normalLowPermissionDtoDoesNotLeakInternalAudit']=reader_code in {200,403,404} and not (keys(reader_body)&{'grounding_audit','grounding_reason','supportingText','supporting_text','checks','evidence_quotes'})
            report['normalReaderResponse']=reader_body;report['normalReaderHttpStatus']=reader_code
        except Exception as failure:
            checks['normalApiReadbackCompleted']=False
            report['normalApiReadbackFailure']=type(failure).__name__+': '+str(failure)
        finally:
            for request in [admin,reader]:
                if request is None: continue
                try: request('/auth/logout',{})
                except Exception as failure: report.setdefault('logoutFailures',[]).append(type(failure).__name__)
    report['status']='PASS_ACTUAL_STABLE_DERIVED_PUBLICATION' if all(checks.values()) else 'FAIL_ACTUAL_STABLE_DERIVED_PUBLICATION'
    report['boundary']='Actual selected normal Graph/Wiki task+current process identity, native current audits/ACL/full input/output hashes, physical PG/MinIO/Milvus readback and normal API permissions. No inference/state mutation or semantic formal-proof claim; complete histories retained.'
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status=report['status'],passed=sum(checks.values()),failed=[k for k,v in checks.items() if not v])))
    raise SystemExit(0 if report['status'].startswith('PASS') else 1)


if __name__=='__main__': main()
