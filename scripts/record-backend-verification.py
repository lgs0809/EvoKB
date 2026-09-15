#!/usr/bin/env python3
"""Bind completed Maven verification to its actual artifact and backend sources."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]


def record(log,output):
    if output.exists():raise ValueError('Use a fresh result path')
    content=log.read_text()
    executed=set(re.findall(r'^\[INFO\] Running ([\w.$]+)\s*$',content,re.MULTILINE))
    counts=dict(tests=0,failures=0,errors=0,skipped=0);suites=[];skips=[];ignored=[]
    observed=set();stale=[]
    started=getattr(log.stat(),'st_birthtime',None)
    for path in sorted((ROOT/'java').glob('*/target/surefire-reports/TEST-*.xml')):
        suite=ET.parse(path).getroot();values={key:int(suite.get(key,0)) for key in counts}
        name=suite.get('name')
        if name not in executed:
            ignored.append(name);continue
        if name in observed:raise ValueError('Duplicate executed suite report: '+name)
        observed.add(name)
        if started is not None and path.stat().st_mtime<started:stale.append(name)
        for key,value in values.items():counts[key]+=value
        suites.append({'suite':suite.get('name'),**values,'reportSha256':hashlib.sha256(path.read_bytes()).hexdigest()})
        for test in suite.findall('testcase'):
            if test.find('skipped') is not None:skips.append({'class':test.get('classname'),'test':test.get('name'),'reason':test.find('skipped').get('message')})
    jar=ROOT/'java/ekb-bootstrap/target/evokb.jar'
    success=('BUILD SUCCESS' in content and counts['tests']>0
             and counts['failures']==counts['errors']==0 and executed==observed and not stale)
    sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted((ROOT/'java').rglob('*')) if p.is_file() and 'target' not in p.parts
             and (p.name=='pom.xml' or '/src/' in str(p))}
    before_path=log.with_suffix('.sources-before.json')
    sources_stable=(json.loads(before_path.read_text())==sources) if before_path.exists() else None
    success=success and sources_stable is not False
    result={'status':'PASS_FULL_JAVA_VERIFY' if success else 'FAIL_FULL_JAVA_VERIFY',
            'actualOperationTime':datetime.now(timezone.utc).isoformat(),'counts':counts,
            'passed':counts['tests']-counts['skipped']-counts['failures']-counts['errors'],
            'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'logSha256':hashlib.sha256(log.read_bytes()).hexdigest(),
            'executedSuites':sorted(executed),'missingExecutedReports':sorted(executed-observed),
            'staleExecutedReports':stale,'ignoredUnexecutedReports':ignored,
            'reportFreshnessClock':'LOG_CREATION_TIME' if started is not None else 'UNAVAILABLE',
            'backendSources':sources,'suites':suites,'skips':skips}
    result['sourcesStableDuringVerification']=sources_stable
    result['sourceManifestBeforeSha256']=hashlib.sha256(before_path.read_bytes()).hexdigest() if before_path.exists() else None
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ['backendSources','suites']},ensure_ascii=False))
    if not success:raise SystemExit(1)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--log',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();record(args.log,args.output)
