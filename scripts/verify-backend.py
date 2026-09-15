#!/usr/bin/env python3
"""Reproducible full Maven verification with immutable evidence and stable source/artifact binding."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]


def sources():
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((ROOT/'java').rglob('*')) if p.is_file() and 'target' not in p.parts
            and (p.name=='pom.xml' or '/src/' in str(p))}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    output=args.output.resolve()
    paths={suffix:output.with_suffix(suffix) for suffix in
           ('.json','.log','.format.log','.sources-before.json','.exit.json')}
    if any(p.exists() for p in paths.values()):
        raise ValueError('Use a fresh output stem; previous attempts are retained')
    output.parent.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ)
    env['JAVA_HOME']=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()
    env['PATH']=env['JAVA_HOME']+'/bin:'+env['PATH']
    env['MAVEN_OPTS']='-Xmx512m -XX:ActiveProcessorCount=2'
    with paths['.format.log'].open('x') as stream:
        formatted=subprocess.run(['mvn','-f','java/pom.xml','spotless:apply'],cwd=ROOT,
                                 env=env,stdout=stream,stderr=subprocess.STDOUT)
    if formatted.returncode:
        raise SystemExit(formatted.returncode)
    before=sources()
    paths['.sources-before.json'].write_text(json.dumps(before,indent=2)+'\n')
    command=['mvn','-f','java/pom.xml','verify','-DargLine=-XX:ActiveProcessorCount=2 -Xmx512m']
    started=datetime.now(timezone.utc).isoformat()
    clock=time.monotonic()
    with paths['.log'].open('x') as stream:
        run=subprocess.run(command,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT)
    after=sources()
    result=dict(command=command,javaHome=env['JAVA_HOME'],mavenOpts=env['MAVEN_OPTS'],
                startedAt=started,finishedAt=datetime.now(timezone.utc).isoformat(),
                elapsedSeconds=time.monotonic()-clock,exitCode=run.returncode,
                sourcesUnchanged=before==after,
                changedSources=sorted(k for k in set(before)|set(after) if before.get(k)!=after.get(k)))
    paths['.exit.json'].write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result),flush=True)
    recorded=subprocess.run(['python3','scripts/record-backend-verification.py','--log',str(paths['.log']),
                             '--output',str(paths['.json'])],cwd=ROOT)
    raise SystemExit(run.returncode or recorded.returncode or int(before!=after))


if __name__=='__main__':
    main()
