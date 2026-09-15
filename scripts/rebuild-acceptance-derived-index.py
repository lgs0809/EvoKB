#!/usr/bin/env python3
"""Preserve the old isolated index, copy its exact documents into a properly mapped new index.

The app must be stopped and only evokb_acceptance_derived_* indexes are accepted.
Uses the current tested JAR's adapter for the vector mapping and native OpenSearch
reindex with op_type=create. No delete, success-state change or model call.
After exact content verification, update only the isolated deployment index name.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:19210'


def api(method,path,data=None):
    request=urllib.request.Request(BASE+path,method=method,
        data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=90) as response:
        return json.load(response)


def documents(index):
    response=api('POST','/'+index+'/_search?scroll=1m',{'size':100,'sort':['_doc'],'query':{'match_all':{}}})
    result={}
    while True:
        hits=response['hits']['hits']
        if not hits:break
        result.update({h['_id']:h['_source'] for h in hits})
        response=api('POST','/_search/scroll',{'scroll':'1m','scroll_id':response['_scroll_id']})
    return result


def run(args):
    if any(not re.fullmatch(r'evokb_acceptance_derived_[a-z0-9_]{1,48}',i) for i in [args.source,args.target]) or args.source==args.target:
        raise ValueError('Use distinct isolated acceptance index names')
    if args.report.exists():raise ValueError('Preserve existing evidence; choose a new report')
    try:
        urllib.request.urlopen('http://127.0.0.1:18480/actuator/health',timeout=2)
    except (urllib.error.URLError,TimeoutError):pass
    else:raise ValueError('Stop the managed app before copying an index; do not race active writers')
    process=ROOT/'.tmp/acceptance/process.json'
    if process.exists():
        state=json.loads(process.read_text())
        current=subprocess.run(['ps','-p',str(state['pid']),'-o','lstart='],capture_output=True,text=True).stdout.strip()
        if current==state.get('processBirth'):raise ValueError('Managed process is still exiting')
    try:api('GET','/'+args.target)
    except urllib.error.HTTPError as error:
        if error.code!=404:raise
    else:raise ValueError('Target exists; do not replace or clear it')
    jar=ROOT/'java/ekb-bootstrap/target/evokb.jar'
    sha=hashlib.sha256(jar.read_bytes()).hexdigest()
    verification=json.loads(args.verification.read_text())
    if verification.get('status')!='PASS_FULL_JAVA_VERIFY' or verification.get('jarSha256')!=sha:
        raise ValueError('Use the exact successfully verified artifact')
    source=documents(args.source)
    mapping=api('GET','/'+args.source+'/_mapping')
    if not source:raise ValueError('No empty-index acceptance')
    if any(len(d.get('embedding',[]))!=1024 for d in source.values()):raise ValueError('Unexpected existing embedding contract; never fabricate vectors')
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.with_suffix('.source.json').write_text(json.dumps({'mapping':mapping,'documents':source},ensure_ascii=False,indent=2)+'\n')
    with tempfile.TemporaryDirectory(prefix='evokb-derived-rebuild-') as temporary:
        tmp=Path(temporary)
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(tmp/Path(name).name).write_bytes(archive.read(name))
        java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17']).decode().strip())/'bin'
        cp=str(tmp/'*')+os.pathsep+str(tmp)
        subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(tmp),str(ROOT/'scripts/DerivedSearchIndexProbe.java')],check=True,capture_output=True,timeout=45)
        subprocess.run([str(java/'java'),'-Xmx192m','-cp',cp,'DerivedSearchIndexProbe',args.target,'1024'],check=True,capture_output=True,timeout=90)
        copied=api('POST','/_reindex?refresh=true',{'source':{'index':args.source},'dest':{'index':args.target,'op_type':'create'}})
        if copied.get('failures') or copied.get('version_conflicts') or copied.get('created')!=len(source):raise ValueError('Incomplete native reindex; keep target and diagnostic state')
        target=documents(args.target)
        preserved=documents(args.source)
        if target!=source or preserved!=source:raise ValueError('Exact ID/content/current-state comparison failed; do not switch deployment')
        vector=tmp/'vector.json';vector.write_text(json.dumps({'vector':next(iter(source.values()))['embedding']}))
        search=json.loads(subprocess.check_output([str(java/'java'),'-Xmx192m','-cp',cp,'DerivedSearchIndexProbe',args.target,'1024',str(vector)],timeout=90))
    env=ROOT/'.env.acceptance'
    lines=env.read_text().splitlines();old=[line for line in lines if line.startswith('EKB_DERIVED_INDEX=')]
    if old!=['EKB_DERIVED_INDEX='+args.source]:raise ValueError('Deployment changed concurrently; do not switch')
    pending=env.with_suffix('.acceptance.pending')
    pending.write_text('\n'.join('EKB_DERIVED_INDEX='+args.target if line.startswith('EKB_DERIVED_INDEX=') else line for line in lines)+'\n');pending.chmod(0o600)
    os.replace(pending,env)
    result={'status':'PASS_PRESERVED_OUT_OF_PLACE_DERIVED_INDEX_REBUILD','actualOperationTime':datetime.now(timezone.utc).isoformat(),
        'sourceIndex':args.source,'targetIndex':args.target,'jarSha256':sha,'documents':len(source),
        'exactDocumentsPreserved':True,'oldIndexStillPresent':True,'currentStateAndVectorsUnchanged':True,
        'nativeReindex':copied,'actualCompiledAdapterVectorSearch':search,'modelsCalled':0,
        'boundary':'Exact existing derived documents, vectors and active flags copied. Source preserved. Does not assert QA correctness, ACL, new embeddings or full multi-lane query success.'}
    args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ['status','sourceIndex','targetIndex','documents','jarSha256']},ensure_ascii=False))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',required=True);p.add_argument('--target',required=True)
    p.add_argument('--report',type=Path,required=True);p.add_argument('--verification',type=Path,required=True)
    run(p.parse_args())
