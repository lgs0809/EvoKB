"""Read-only PG/MinIO/Milvus source gap audit against a frozen formal manifest."""
from native_probe_runtime import java17_bin
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

R=Path(__file__).resolve().parents[1]
JAVA=java17_bin()
p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--logical-name',action='append',default=[]);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh audit output required')
a.output.parent.mkdir(parents=True,exist_ok=True)
state=json.loads((R/'.tmp/target/process.json').read_text());jar=Path(state['artifact'])
if hashlib.sha256(jar.read_bytes()).hexdigest()!=state['jarSha256']:raise SystemExit('Immutable runtime artifact changed')
manifest=json.loads(a.manifest.read_text())
if a.logical_name and len(set(a.logical_name))!=len([d for d in manifest['documents'] if d['logicalName'] in a.logical_name]):raise SystemExit('Unknown or ambiguous frozen logical name')
for doc in manifest['documents']:
 if hashlib.sha256((a.manifest.parent/doc['path']).read_bytes()).hexdigest()!=doc['sha256']:raise SystemExit('Frozen original changed')
frozen=a.output.with_suffix('.frozen');frozen.mkdir();helper=frozen/'FormalRawImportAuditProbe.java';helper.write_bytes((R/'scripts/FormalRawImportAuditProbe.java').read_bytes())
binding={'readerArtifact':state,'jarSha256':state['jarSha256'],'manifestSha256':hashlib.sha256(a.manifest.read_bytes()).hexdigest(),'helperSourceSha256':hashlib.sha256(helper.read_bytes()).hexdigest(),'actualAuditTime':datetime.now(timezone.utc).isoformat(),'modelRequests':0,'sqlMutations':0,'selectedLogicalNames':a.logical_name}
settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema=public&stringtype=unspecified')
try:
 with tempfile.TemporaryDirectory(prefix='evokb-formal-raw-readonly-') as directory:
  work=Path(directory);classes=work/'classes';classes.mkdir();libraries=[];hashes={}
  with zipfile.ZipFile(jar) as archive:
   for name in archive.namelist():
    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
     target=work/Path(name).name;data=archive.read(name);target.write_bytes(data);libraries.append(target);hashes[target.name]=hashlib.sha256(data).hexdigest()
  binding['dependencySha256']=hashes;cp=os.pathsep.join(map(str,libraries))
  compiled=subprocess.run([str(JAVA/'javac'),'-J-Xmx128m','-J-XX:ActiveProcessorCount=2','-cp',cp,'-d',str(classes),str(helper)],capture_output=True,text=True,timeout=90)
  a.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr);compiled.check_returncode()
  binding['helperClassSha256']={str(x.relative_to(classes)):hashlib.sha256(x.read_bytes()).hexdigest() for x in classes.rglob('*.class')}
  with a.output.with_suffix('.runtime.log').open('w') as log:
   result=subprocess.run([str(JAVA/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(classes)+os.pathsep+cp,'FormalRawImportAuditProbe',str(a.output),str(a.manifest),*a.logical_name],stdout=log,stderr=subprocess.STDOUT,env=env,timeout=180)
  binding['processExitCode']=result.returncode
except Exception as error:binding['failure']=type(error).__name__+': '+(str(error.returncode) if isinstance(error,subprocess.CalledProcessError) else str(error))
report=json.loads(a.output.read_text()) if a.output.exists() else {'status':'FAIL_READONLY_FORMAL_RAW_IMPORT_AUDIT'}
report.update(binding)
if report.get('processExitCode')!=0:report['status']='FAIL_READONLY_FORMAL_RAW_IMPORT_AUDIT'
report['runtimeUnchangedDuringAudit']=json.loads((R/'.tmp/target/process.json').read_text())==state
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:report.get(k) for k in ['status','sourceCount','importStatusCounts','runtimeUnchangedDuringAudit','failure']},ensure_ascii=False))
raise SystemExit(0 if report['status']=='READONLY_FORMAL_RAW_IMPORT_GAP_AUDIT' else 1)
