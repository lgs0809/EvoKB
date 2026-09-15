"""Real local Redis/PG probe using already frozen exact repository classes and matching Boot dependencies."""
from native_probe_runtime import java17_bin
import argparse,hashlib,json,os,re,subprocess,uuid
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1];p=argparse.ArgumentParser();p.add_argument('--base',type=Path,required=True);p.add_argument('--dependencies',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence required')
base=json.loads(a.base.read_text());dep=json.loads(a.dependencies.read_text())
if base['status']!='PASS_ACTUAL_PG_RAW_OWNERSHIP' or dep['status']!='PASS_EXACT_BOM_ARTIFACTS':raise SystemExit('Frozen successful prerequisite missing')
basework=a.base.with_suffix('.frozen');work=a.output.with_suffix('.frozen');work.mkdir();classes=work/'classes';classes.mkdir();namespace='acceptance_raw_stream_'+uuid.uuid4().hex[:12]
seed=re.sub(r'acceptance_raw_task_[0-9a-f]+',namespace,a.base.with_suffix('.seed.sql').read_text());a.output.with_suffix('.seed.sql').write_text(seed)
r=subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-v','ON_ERROR_STOP=1','--single-transaction','-f','-'],input=seed,capture_output=True,text=True);a.output.with_suffix('.seed.log').write_text(r.stdout+r.stderr);r.check_returncode()
m2=Path.home()/'.m2/repository';extra=[Path(r['path']) for r in dep['records'] if r['path'].endswith('.jar')];extra+=[m2/'org/redisson/redisson/3.27.2/redisson-3.27.2.jar',m2/'io/reactivex/rxjava3/rxjava/3.1.6/rxjava-3.1.6.jar',m2/'javax/cache/cache-api/1.1.1/cache-api-1.1.1.jar'];extra=[x for x in extra if x.exists()]
libs=sorted((basework/'lib').glob('*.jar'));cp=os.pathsep.join(str(x) for x in [basework/'classes',*libs,*extra]);sources=[R/'scripts/RawStreamRecoveryProbe.java',R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/ingestion/RedisRawDeliveryQueue.java'];snapshots={str(x.relative_to(R)):x.read_bytes() for x in sources};frozen=[]
for name,data in snapshots.items():target=work/Path(name).name;target.write_bytes(data);frozen.append(target)
java=java17_bin();result=subprocess.run([str(java/'javac'),'-J-Xmx256m','-J-XX:ActiveProcessorCount=2','-cp',cp,'-d',str(classes),*(str(x) for x in frozen)],capture_output=True,text=True);a.output.with_suffix('.compile.log').write_text(result.stdout+result.stderr)
provenance=dict(startedAt=datetime.now(timezone.utc).isoformat(),postgresSchema=namespace,sourceHashes={name:hashlib.sha256(data).hexdigest() for name,data in snapshots.items()},baseEvidence=str(a.base),baseEvidenceSha256=hashlib.sha256(a.base.read_bytes()).hexdigest(),additionalDependencyHashes={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in extra},seedSha256=hashlib.sha256(seed.encode()).hexdigest())
if result.returncode:a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_COMPILE',**provenance),indent=2));raise SystemExit('Actual compile failed; retained evidence')
settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'));env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema='+namespace+'&stringtype=unspecified');result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(classes)+os.pathsep+cp,'RawStreamRecoveryProbe',str(a.output)],env=env,capture_output=True,text=True,timeout=90);a.output.with_suffix('.runtime.log').write_text(result.stdout+result.stderr)
if not a.output.exists():a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_RUNTIME',**provenance),indent=2));raise SystemExit('Actual runtime failed; retained log')
report=json.loads(a.output.read_text());report.update(provenance);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=report.get('checks'),postgresSchema=namespace),ensure_ascii=False));raise SystemExit(result.returncode)
