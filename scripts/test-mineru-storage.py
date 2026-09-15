"""Compile exact owned sources against immutable runtime deps; actual MinIO/parser, no inference."""
from native_probe_runtime import java17_bin
import argparse,hashlib,io,json,os,subprocess,tempfile,zipfile
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--fixture',type=Path,required=True);p.add_argument('--tokenizer',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Use a fresh evidence output path')
settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
jar=R/'java/ekb-bootstrap/target/evokb.jar';frozen=jar.read_bytes()
sources=[R/'scripts/MinerUStorageProbe.java',*(R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/parser'/name for name in ['MinerUPipelineAdapter.java','JacksonParsedArtifactEncoder.java']),R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/LocalEmbeddingTokenizer.java',*(R/'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw'/name for name in ['RawKnowledgeBuilder.java','RawTokenCounter.java','RawChunkProfile.java'])]
pinned=[Path.home()/'.m2/repository/org/jsoup/jsoup/1.21.2/jsoup-1.21.2.jar',Path.home()/'.m2/repository/ai/djl/huggingface/tokenizers/0.32.0/tokenizers-0.32.0.jar',Path.home()/'.m2/repository/ai/djl/api/0.32.0/api-0.32.0.jar'];java=java17_bin()
with tempfile.TemporaryDirectory(prefix='evokb-native-office-storage-') as directory:
 d=Path(directory);libs=[]
 with zipfile.ZipFile(io.BytesIO(frozen)) as archive:
  for entry in archive.namelist():
   if entry.startswith('BOOT-INF/classes/') and not entry.endswith('/'):
    path=d/'boot'/entry[len('BOOT-INF/classes/'):];path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(entry))
   elif entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
    path=d/'lib'/Path(entry).name;path.parent.mkdir(exist_ok=True);path.write_bytes(archive.read(entry));libs.append(path)
 cp=os.pathsep.join([str(d/'boot'),*(str(x) for x in pinned),*(str(x) for x in libs)])
 compiled=subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(d),*(str(x) for x in sources)],capture_output=True,text=True)
 a.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr)
 if compiled.returncode:raise SystemExit('Native parser/storage source compile failed; retained log')
 env=dict(os.environ,PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
 try:
  result=subprocess.run([str(java/'java'),'-XX:ActiveProcessorCount=2','-Xmx256m','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(d)+os.pathsep+cp,'cn.lgs.knowledgebase.application.raw.MinerUStorageProbe',str(a.tokenizer),str(a.fixture),str(a.output)],env=env,capture_output=True,text=True,timeout=45)
 except subprocess.TimeoutExpired as expired:
  a.output.with_suffix('.runtime.log').write_bytes((expired.stdout or b'')+(expired.stderr or b''))
  partial=json.loads(a.output.read_text()) if a.output.exists() else {}
  partial.update(status='FAIL_ACTUAL_STORAGE_TIMEOUT',timeoutSeconds=45,sourceHashes={str(x.relative_to(R)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sources},dependencyJarSha256=hashlib.sha256(frozen).hexdigest());a.output.write_text(json.dumps(partial,ensure_ascii=False,indent=2));raise SystemExit('Actual storage timeout; failure/log/progress retained')
 a.output.with_suffix('.runtime.log').write_text(result.stdout+result.stderr)
 if result.returncode and not a.output.exists():raise SystemExit('Actual parser/storage failed; retained log')
report=json.loads(a.output.read_text());report.update(createdAt=datetime.now(timezone.utc).isoformat(),sourceHashes={str(x.relative_to(R)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sources},dependencyJarSha256=hashlib.sha256(frozen).hexdigest(),fixtureSha256=hashlib.sha256(a.fixture.read_bytes()).hexdigest());a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=report['checks']),ensure_ascii=False));raise SystemExit(result.returncode)
