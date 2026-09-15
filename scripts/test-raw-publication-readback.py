"""Read-only cross-resource proof bound to the exact deployed immutable artifact."""
from native_probe_runtime import java17_bin
import argparse,json,io,zipfile,subprocess,hashlib,os
from pathlib import Path
from datetime import datetime,timezone
R=Path(__file__).resolve().parents[1];p=argparse.ArgumentParser();p.add_argument('--logical-name',required=True);p.add_argument('--parser-name',default='mineru-hybrid-engine');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh attempt required')
state=json.loads((R/'.tmp/target/process.json').read_text());jar=Path(state['artifact']).read_bytes();work=a.output.with_suffix('.frozen');work.mkdir();libs=[]
with zipfile.ZipFile(io.BytesIO(jar)) as z:
 for name in z.namelist():
  if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
   target=work/'lib'/Path(name).name;target.parent.mkdir(exist_ok=True);target.write_bytes(z.read(name));libs.append(str(target))
source=(R/'scripts/RawPublicationReadbackProbe.java').read_bytes();frozen=work/'RawPublicationReadbackProbe.java';frozen.write_bytes(source);classes=work/'classes';classes.mkdir();java=java17_bin();cp=os.pathsep.join(libs);r=subprocess.run([str(java/'javac'),'-J-Xmx128m','-J-XX:ActiveProcessorCount=2','-cp',cp,'-d',str(classes),str(frozen)],capture_output=True,text=True);a.output.with_suffix('.compile.log').write_text(r.stdout+r.stderr)
if r.returncode:a.output.write_text(json.dumps(dict(status='FAIL_READONLY_PROBE_COMPILE',deployedArtifact=state),indent=2));raise SystemExit(1)
settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'));env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?stringtype=unspecified',PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD']);r=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(classes)+os.pathsep+cp,'cn.lgs.knowledgebase.application.raw.RawPublicationReadbackProbe',str(a.output),a.logical_name,settings['EKB_EMBED_TOKENIZER_MANIFEST'],a.parser_name],env=env,capture_output=True,text=True,timeout=50);a.output.with_suffix('.runtime.log').write_text(r.stdout+r.stderr)
if not a.output.exists():a.output.write_text(json.dumps(dict(status='FAIL_READONLY_PROBE_RUNTIME',deployedArtifact=state),indent=2));raise SystemExit(1)
report=json.loads(a.output.read_text());report.update(actualReadbackAt=datetime.now(timezone.utc).isoformat(),deployedArtifact=state,probeSourceSha256=hashlib.sha256(source).hexdigest(),artifactBytesSha256=hashlib.sha256(jar).hexdigest());a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=report['checks'],failure=report.get('failure')),ensure_ascii=False));raise SystemExit(r.returncode)
