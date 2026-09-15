#!/usr/bin/env python3
"""Read actual entry/PG/MinIO/Milvus outputs, preserving the original generation artifact identity."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def postgres_instant(value):
    # PostgreSQL omits trailing fractional zeroes; Python 3.9 only accepts 3/6 digits.
    normalized=re.sub(r'\.(\d{1,6})(?=[+-]\d\d:\d\d$)',lambda match:'.'+match.group(1).ljust(6,'0'),value.replace('Z','+00:00'))
    return datetime.fromisoformat(normalized)


def select_observed_execution(prior, version, task_id=None, current_process=None,
                              expected_kind='CONCEPT', subject_prefix='summary/'):
    history=prior['database']['tasks']
    if task_id:
        rows=[t for t in history if t['task_id']==task_id]
        if len(rows)!=1:raise ValueError('The specified actual generation task is absent or duplicated in the complete observation')
        original=rows[0]
        if original['task_type']!=expected_kind or original['version_id']!=version or original['status']!='SUCCEEDED':
            raise ValueError('The specified new generation task did not succeed for this source/type; history cannot substitute for it')
        if not original.get('subject_id','').startswith(subject_prefix):
            raise ValueError('Normal regenerated task must bind its exact input subject identity')
        if current_process is None or prior['process']['jarSha256']!=current_process['jarSha256']:
            raise ValueError('Normal regenerated execution must be observed under the current immutable artifact')
        if prior['process']['startedAt']!=current_process['startedAt'] or prior['process']['pid']!=current_process['pid']:
            raise ValueError('The observed regeneration belongs to another process incarnation')
    else:
        succeeded=[t for t in history if t['task_type']=='CONCEPT' and t['version_id']==version and t['status']=='SUCCEEDED']
        if len(succeeded)!=1:raise ValueError('An earlier observed successful actual Concept execution is required')
        original=succeeded[0]
    executed=prior['process']
    if not original.get('started_at') or postgres_instant(original['started_at'])<=postgres_instant(executed['startedAt']):
        raise ValueError('Original observation does not bind this execution to its running artifact')
    return original


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',required=True)
    parser.add_argument('--execution-proof',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--projection-task',help='Exact normal durable Concept projection repair task UUID')
    parser.add_argument('--concept-task',help='Exact normal GUI/API/observer task UUID; preserve all history while inspecting only its new publication')
    args=parser.parse_args();uuid.UUID(args.version)
    if args.output.exists() or any(args.output.with_suffix(suffix).exists() for suffix in ['.json','.probe.java','.compile.log','.runtime.log']):raise ValueError('Use a fresh output path')
    if args.concept_task and args.projection_task:raise ValueError('Select one actual task kind')
    projection_task=args.projection_task
    if projection_task:args.concept_task=projection_task
    if args.concept_task:uuid.UUID(args.concept_task)
    state=json.loads((ROOT/'.tmp/target/process.json').read_text())
    artifact=Path(state['artifact']);jar=artifact.read_bytes()
    if hashlib.sha256(jar).hexdigest()!=state['jarSha256']:raise ValueError('Immutable deployed reader identity changed')
    prior=json.loads(args.execution_proof.read_text())
    executed=prior['process']
    try:
        original=select_observed_execution(prior,args.version,args.concept_task,state,subject_prefix="command/" if projection_task else "summary/")
    except ValueError as failure:
        if not args.concept_task:raise
        report=dict(status='FAIL_ACTUAL_CONCEPT_EXECUTION_BINDING',failure=str(failure),
                    selectedConceptTaskId=args.concept_task,sourceVersion=args.version,
                    completeObservedTaskHistory=prior['database']['tasks'],
                    completeObservedEntryHistory=prior['database'].get('entries',[]),
                    process=state,executionProofSha256=hashlib.sha256(args.execution_proof.read_bytes()).hexdigest(),
                    boundary='No native reader/model run. The specified new task failed validation; no historic success was substituted.')
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(dict(status=report['status'],selectedTask=args.concept_task,failure=str(failure))))
        raise SystemExit(1)
    settings=dict(line.split('=',1) for line in (ROOT/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
    java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin'
    source=ROOT/'scripts/StableEntryReadbackProbe.java';source_bytes=source.read_bytes()
    args.output.with_suffix('.probe.java').write_bytes(source_bytes)
    with tempfile.TemporaryDirectory(prefix='evokb-entry-readback-') as temp:
        work=Path(temp)
        frozen=work/source.name;frozen.write_bytes(source_bytes)
        with zipfile.ZipFile(artifact) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work/Path(name).name).write_bytes(archive.read(name))
                elif name.startswith('BOOT-INF/classes/') and name.endswith('.class'):
                    path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))
        cp=os.pathsep.join((str(work/'*'),str(work),str(work/'BOOT-INF/classes')))
        compiled=subprocess.run([str(java/'javac'),'-J-Xmx128m','-cp',cp,'-d',str(work),str(frozen)],capture_output=True,text=True,timeout=60)
        args.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr);compiled.check_returncode()
        env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL=settings['EKB_JDBC_URL'],
                 PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'],
                 PROBE_KNOWLEDGE_BASE=settings.get('EKB_KNOWLEDGE_BASE_ID','enterprise'))
        result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-cp',cp,
            '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','StableEntryReadbackProbe',str(args.output.resolve()),args.version,*([args.concept_task] if args.concept_task else [])],
            env=env,capture_output=True,timeout=120)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout+result.stderr)
    report=json.loads(args.output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(),readerJarSha256=state['jarSha256'],
        actualGenerationJarSha256=executed['jarSha256'],observedExecution=original,
        executionProofSha256=hashlib.sha256(args.execution_proof.read_bytes()).hexdigest(),
        probeSourceSha256=hashlib.sha256(source_bytes).hexdigest())
    if args.concept_task:
        report.update(selectedConceptTaskId=args.concept_task,completeObservedTaskHistory=prior['database']['tasks'],
                      completeObservedEntryHistory=prior['database'].get('entries',[]),
                      readbackScope='Selected normal task Summary and actually rebuilt member entries; historical unrebuilt entries are listed but are not claimed as new outputs', selectedTaskKind='NORMAL_CONCEPT_PROJECTION_COMMAND' if projection_task else 'NORMAL_CONCEPT_GENERATION')
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status=report['status'],passed=sum(report['checks'].values()),failed=[k for k,v in report['checks'].items() if not v],
        readerJar=report['readerJarSha256'],actualGenerationJar=report['actualGenerationJarSha256'])))
    raise SystemExit(result.returncode)


if __name__=='__main__':main()
