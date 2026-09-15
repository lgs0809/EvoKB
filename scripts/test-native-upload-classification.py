"""Retained native source-overlay classification regression, never real-model/deployed-HTTP proof."""
from native_probe_runtime import java17_bin
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

R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--tokenizer-manifest',type=Path,required=True)
a=p.parse_args()
if a.output.exists() or a.output.with_suffix('.frozen').exists():raise SystemExit('Fresh evidence path required')
frozen=a.output.with_suffix('.frozen');frozen.mkdir(parents=True)
java=java17_bin()
sources=[*sorted((R/'java/ekb-domain/src/main/java').rglob('*.java')),*sorted((R/'java/ekb-application/src/main/java').rglob('*.java'))]
for relative in ['java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcKnowledgeRepository.java','java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcIngestionRepository.java','java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/ingestion/DurableIngestionWorker.java','java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/upload/JdbcMultipartUploadRepository.java','java/ekb-interfaces/src/main/java/cn/lgs/knowledgebase/interfaces/http/EnterpriseApiController.java','java/ekb-interfaces/src/main/java/cn/lgs/knowledgebase/interfaces/http/HttpViews.java','java/ekb-interfaces/src/main/java/cn/lgs/knowledgebase/interfaces/http/SafeBatchUploadStager.java','java/ekb-interfaces/src/main/java/cn/lgs/knowledgebase/interfaces/http/UploadSessionController.java','scripts/NativeInitialClassificationProbe.java']:
 sources.append(R/relative)
snapshots={str(x.relative_to(R)):x.read_bytes() for x in sources}
paths=[]
for name,data in snapshots.items():
 target=frozen/'src'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);paths.append(target)
token=json.loads(a.tokenizer_manifest.read_text());assets={name:hashlib.sha256((a.tokenizer_manifest.parent/name).read_bytes()).hexdigest() for name in token['files']}
if assets!=token['files']:raise SystemExit('Frozen tokenizer assets changed')
schema='raw_classification_'+uuid.uuid4().hex[:12]
binding={'jarSha256':hashlib.sha256(a.jar.read_bytes()).hexdigest(),'sourceHashes':{name:hashlib.sha256(data).hexdigest() for name,data in snapshots.items()},'postgresSchema':schema,'milvusDatabase':schema,'tokenizerAssetsSha256':assets,'modelRevision':token['modelRevision'],'tokenizerRevision':token['tokenizerRevision'],'startedAt':datetime.now(timezone.utc).isoformat(),'provenance':'FROZEN_SOURCE_OVERLAY_WITH_EXPLICIT_SOURCE_MIGRATIONS','deploymentClaim':False,'modelRequests':0}
try:
 with tempfile.TemporaryDirectory(prefix='evokb-native-classification-') as temp:
  work=Path(temp);libraries=[]
  with zipfile.ZipFile(a.jar) as archive:
   for name in archive.namelist():
    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
     path=work/Path(name).name;path.write_bytes(archive.read(name));libraries.append(path)
  binding['dependencySha256']={x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in libraries};cp=os.pathsep.join(map(str,libraries))
  classes=frozen/'classes';classes.mkdir()
  compiled=subprocess.run([str(java/'javac'),'-J-Xmx256m','-J-XX:ActiveProcessorCount=2','-cp',cp,'-d',str(classes),*map(str,paths)],capture_output=True,text=True,timeout=120)
  a.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr);compiled.check_returncode()
  binding['classSha256']={str(x.relative_to(classes)):hashlib.sha256(x.read_bytes()).hexdigest() for x in classes.rglob('*.class')}
  migrations={x.name:x.read_bytes() for x in (R/'java/ekb-bootstrap/src/main/resources/db/migration').glob('V*.sql')}
  sql='CREATE SCHEMA '+schema+';SET search_path TO '+schema+';\n'+''.join(migrations[name].decode() for name in sorted(migrations,key=lambda x:int(x.split('__')[0][1:])))
  a.output.with_suffix('.seed.sql').write_text(sql);binding['migrationSha256']={name:hashlib.sha256(data).hexdigest() for name,data in migrations.items()}
  applied=subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-v','ON_ERROR_STOP=1','--single-transaction','-f','-'],input=sql,capture_output=True,text=True,timeout=90)
  a.output.with_suffix('.seed.log').write_text(applied.stdout+applied.stderr);applied.check_returncode()
  settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
  env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema='+schema+'&stringtype=unspecified',PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
  result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(classes)+os.pathsep+cp,'cn.lgs.knowledgebase.application.raw.NativeInitialClassificationProbe',str(a.output),str(frozen/'originals'),str(a.tokenizer_manifest),schema],capture_output=True,text=True,env=env,timeout=240)
  a.output.with_suffix('.runtime.log').write_text(result.stdout+result.stderr);binding['processExitCode']=result.returncode
except Exception as error:binding['failure']=type(error).__name__+': '+(str(error.returncode) if isinstance(error,subprocess.CalledProcessError) else str(error))
report=json.loads(a.output.read_text()) if a.output.exists() else {'status':'FAIL_NATIVE_INITIAL_CLASSIFICATION','checks':{}}
report.update(binding);report['sourcesStillMatchCompileSnapshot']=all(x.read_bytes()==snapshots[str(x.relative_to(R))] for x in sources)
report['finishedAt']=datetime.now(timezone.utc).isoformat()
if binding.get('processExitCode')!=0:report['status']='FAIL_NATIVE_INITIAL_CLASSIFICATION'
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:report.get(k) for k in ['status','sourcesStillMatchCompileSnapshot','failure','postgresSchema','modelRequests']},ensure_ascii=False))
print(json.dumps({'passedChecks':sum(report.get('checks',{}).values()),'failedChecks':[k for k,v in report.get('checks',{}).items() if not v]},ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
