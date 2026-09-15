"""Read an actual normal Summary publication while preserving separate producer/reader identities."""
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
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--task',required=True)
parser.add_argument('--version',required=True)
parser.add_argument('--original',type=Path,required=True)
parser.add_argument('--execution-proof',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args();uuid.UUID(args.task);uuid.UUID(args.version)
if any(args.output.with_suffix(s).exists() for s in ['.json','.probe.java','.compile.log','.runtime.log']):raise ValueError('Use a fresh report stem')
args.output.parent.mkdir(parents=True,exist_ok=True)
producer=json.loads(args.execution_proof.read_text());reader=json.loads((ROOT/'.tmp/target/process.json').read_text())
artifact=Path(reader['artifact']);jar=artifact.read_bytes()
if hashlib.sha256(jar).hexdigest()!=reader['jarSha256']:raise ValueError('Reader artifact identity changed')
if hashlib.sha256(Path(producer['artifact']).read_bytes()).hexdigest()!=producer['jarSha256']:raise ValueError('Producer immutable artifact identity changed')
settings=dict(line.split('=',1) for line in (ROOT/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
source=(ROOT/'scripts/SummaryPublicationReadbackProbe.java').read_bytes();args.output.with_suffix('.probe.java').write_bytes(source)
java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin'
with tempfile.TemporaryDirectory(prefix='evokb-summary-readback-') as temp:
    work=Path(temp)
    with zipfile.ZipFile(artifact) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(work/Path(name).name).write_bytes(archive.read(name))
    src=work/'SummaryPublicationReadbackProbe.java';src.write_bytes(source)
    cp=str(work/'*')+os.pathsep+str(work)
    compiled=subprocess.run([str(java/'javac'),'-J-Xmx128m','-cp',cp,'-d',str(work),str(src)],capture_output=True,text=True)
    args.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr);compiled.check_returncode()
    env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL=settings['EKB_JDBC_URL'],PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
    result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
        '-cp',cp,'SummaryPublicationReadbackProbe',str(args.output.resolve()),args.task,args.version,producer['startedAt'],str(args.original.resolve())],env=env,capture_output=True,timeout=180)
    args.output.with_suffix('.runtime.log').write_bytes(result.stdout+result.stderr)
report=json.loads(args.output.read_text());report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(),producerProcess=producer,readerProcess=reader,
    executionProofSha256=hashlib.sha256(args.execution_proof.read_bytes()).hexdigest(),probeSourceSha256=hashlib.sha256(source).hexdigest(),
    readerUnchanged=json.loads((ROOT/'.tmp/target/process.json').read_text())==reader)
args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(status=report['status'],passed=sum(report['checks'].values()),failed=[k for k,v in report['checks'].items() if not v],producerJar=producer['jarSha256'],readerJar=reader['jarSha256']),ensure_ascii=False))
raise SystemExit(result.returncode)
