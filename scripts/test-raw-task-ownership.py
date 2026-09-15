"""Frozen actual raw repository sources + retained isolated PostgreSQL fixtures, lightweight JDK only."""
from native_probe_runtime import java17_bin
import argparse,hashlib,io,json,os,subprocess,uuid,zipfile
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1];p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence output required')
a.output.parent.mkdir(parents=True,exist_ok=True);work=a.output.with_suffix('.frozen');work.mkdir();namespace='acceptance_raw_task_'+uuid.uuid4().hex[:12]
settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
migrations=sorted((p for p in (R/'java/ekb-bootstrap/src/main/resources/db/migration').glob('V*.sql') if int(p.name.split('__')[0][1:])<=14 or p.name.startswith('V16__')),key=lambda p:int(p.name.split('__')[0][1:]));sql='CREATE SCHEMA '+namespace+';SET search_path TO '+namespace+';\n'+''.join(x.read_text() for x in migrations);a.output.with_suffix('.seed.sql').write_text(sql)
r=subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-v','ON_ERROR_STOP=1','--single-transaction','-f','-'],input=sql,capture_output=True,text=True);a.output.with_suffix('.seed.log').write_text(r.stdout+r.stderr);r.check_returncode()
# Include frozen current application/domain contracts so compiling updated Ports never silently uses old contracts.
sources=[*sorted((R/'java/ekb-domain/src/main/java').rglob('*.java')),*sorted((R/'java/ekb-application/src/main/java').rglob('*.java')),R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcIngestionRepository.java',R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw/JdbcRawPublicationWriter.java',R/'scripts/RawTaskOwnershipProbe.java'];snapshots={str(x.relative_to(R)):x.read_bytes() for x in sources}
jar=(R/'java/ekb-bootstrap/target/evokb.jar').read_bytes();libs=[]
with zipfile.ZipFile(io.BytesIO(jar)) as z:
 for name in z.namelist():
  if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
   t=work/'lib'/Path(name).name;t.parent.mkdir(exist_ok=True);t.write_bytes(z.read(name));libs.append(t)
java=java17_bin();frozen=[]
for name,data in snapshots.items():t=work/'src'/name;t.parent.mkdir(parents=True,exist_ok=True);t.write_bytes(data);frozen.append(t)
cp=os.pathsep.join(str(x) for x in libs);classes=work/'classes';classes.mkdir();compiled=subprocess.run([str(java/'javac'),'-J-Xmx256m','-J-XX:ActiveProcessorCount=2','-cp',cp,'-d',str(classes),*(str(x) for x in frozen)],capture_output=True,text=True);a.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr)
provenance=dict(startedAt=datetime.now(timezone.utc).isoformat(),postgresSchema=namespace,sourceHashes={name:hashlib.sha256(data).hexdigest() for name,data in snapshots.items()},dependencyJarSha256=hashlib.sha256(jar).hexdigest(),seedSha256=hashlib.sha256(sql.encode()).hexdigest(),boundary='Native source snapshot compile, not full reactor test or deployment')
if compiled.returncode:a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_COMPILE',**provenance),indent=2));raise SystemExit('Actual compile failed; retained evidence')
env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema='+namespace+'&stringtype=unspecified');result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(classes)+os.pathsep+cp,'RawTaskOwnershipProbe',str(a.output)],env=env,capture_output=True,text=True,timeout=80);a.output.with_suffix('.runtime.log').write_text(result.stdout+result.stderr)
if not a.output.exists():a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_RUNTIME',**provenance),indent=2));raise SystemExit('Actual runtime failed; retained log')
report=json.loads(a.output.read_text());report.update(provenance);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=report.get('checks'),postgresSchema=namespace),ensure_ascii=False));raise SystemExit(result.returncode)
