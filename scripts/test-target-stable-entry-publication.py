#!/usr/bin/env python3
"""Read actual entry/PG/MinIO/Milvus outputs, preserving the original generation artifact identity."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',required=True)
    parser.add_argument('--execution-proof',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();uuid.UUID(args.version)
    if args.output.exists():raise ValueError('Use a fresh output path')
    state=json.loads((ROOT/'.tmp/target/process.json').read_text())
    artifact=Path(state['artifact']);jar=artifact.read_bytes()
    if hashlib.sha256(jar).hexdigest()!=state['jarSha256']:raise ValueError('Immutable deployed reader identity changed')
    prior=json.loads(args.execution_proof.read_text())
    succeeded=[t for t in prior['database']['tasks'] if t['task_type']=='CONCEPT' and t['version_id']==args.version and t['status']=='SUCCEEDED']
    if len(succeeded)!=1:raise ValueError('An earlier observed successful actual Concept execution is required')
    original=succeeded[0]
    executed=prior['process']
    if datetime.fromisoformat(original['started_at'])<=datetime.fromisoformat(executed['startedAt']):
        raise ValueError('Original observation does not bind this execution to its running artifact')
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
                 PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
        result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-cp',cp,
            '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','StableEntryReadbackProbe',str(args.output.resolve()),args.version],
            env=env,capture_output=True,timeout=120)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout+result.stderr)
    report=json.loads(args.output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(),readerJarSha256=state['jarSha256'],
        actualGenerationJarSha256=executed['jarSha256'],observedExecution=original,
        executionProofSha256=hashlib.sha256(args.execution_proof.read_bytes()).hexdigest(),
        probeSourceSha256=hashlib.sha256(source_bytes).hexdigest())
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status=report['status'],passed=sum(report['checks'].values()),failed=[k for k,v in report['checks'].items() if not v],
        readerJar=report['readerJarSha256'],actualGenerationJar=report['actualGenerationJarSha256'])))
    raise SystemExit(result.returncode)


if __name__=='__main__':main()
