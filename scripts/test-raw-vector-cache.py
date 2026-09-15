"""Retained actual PostgreSQL schema, exact current raw cache source and frozen class dependencies."""
from native_probe_runtime import java17_bin
import argparse,hashlib,io,json,os,subprocess,tempfile,uuid,zipfile
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists() or a.output.with_suffix('.seed.sql').exists():raise SystemExit('Fresh evidence prefix required')
settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
namespace='acceptance_raw_cache_'+uuid.uuid4().hex[:12];migration=R/'java/ekb-bootstrap/src/main/resources/db/migration/V13__raw_vector_cache.sql';sql='CREATE SCHEMA '+namespace+'; SET search_path TO '+namespace+';\n'+migration.read_text();a.output.with_suffix('.seed.sql').write_text(sql)
applied=subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-v','ON_ERROR_STOP=1','--single-transaction','-f','-'],input=sql,capture_output=True,text=True);a.output.with_suffix('.seed.log').write_text(applied.stdout+applied.stderr)
if applied.returncode:raise SystemExit('Actual PostgreSQL isolated schema apply failed')
sources=[R/'scripts/RawVectorCacheProbe.java',R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/JdbcRawVectorCache.java',*(R/'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw'/name for name in ['RawEncodingProfile.java','RawVectorCache.java','RawVectorEncoder.java','RawTokenCounter.java','RawChunkProfile.java'])];snapshots={path:path.read_bytes() for path in sources};jar=(R/'java/ekb-bootstrap/target/evokb.jar').read_bytes();java=java17_bin()
with tempfile.TemporaryDirectory(prefix='evokb-native-raw-cache-') as directory:
 d=Path(directory);libs=[]
 with zipfile.ZipFile(io.BytesIO(jar)) as archive:
  for entry in archive.namelist():
   if entry.startswith('BOOT-INF/classes/') and not entry.endswith('/'):
    target=d/'boot'/entry[len('BOOT-INF/classes/'):];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(entry))
   elif entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
    target=d/'lib'/Path(entry).name;target.parent.mkdir(exist_ok=True);target.write_bytes(archive.read(entry));libs.append(target)
 cp=os.pathsep.join([str(d/'boot'),*(str(x) for x in libs)]);frozen=[]
 for path,data in snapshots.items():target=d/path.name;target.write_bytes(data);frozen.append(target)
 compiled=subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(d),*(str(x) for x in frozen)],capture_output=True,text=True);a.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr)
 if compiled.returncode:raise SystemExit('Raw cache probe compile failed; retained log')
 env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema='+namespace+'&stringtype=unspecified')
 result=subprocess.run([str(java/'java'),'-XX:ActiveProcessorCount=2','-Xmx256m','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(d)+os.pathsep+cp,'RawVectorCacheProbe',str(a.output)],env=env,capture_output=True,text=True,timeout=30);a.output.with_suffix('.runtime.log').write_text(result.stdout+result.stderr)
report=json.loads(a.output.read_text());report.update(createdAt=datetime.now(timezone.utc).isoformat(),postgresSchema=namespace,sourceHashes={str(path.relative_to(R)):hashlib.sha256(data).hexdigest() for path,data in snapshots.items()},dependencyJarSha256=hashlib.sha256(jar).hexdigest(),seedSha256=hashlib.sha256(sql.encode()).hexdigest());a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=report['checks'],postgresSchema=namespace),ensure_ascii=False));raise SystemExit(result.returncode)
