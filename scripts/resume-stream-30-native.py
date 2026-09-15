#!/usr/bin/env python3
"""Continue only the original retained Stream30 facts after unrelated dependency recovery."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--prior-report',type=Path,required=True)
p.add_argument('--jar-path',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh output required')
prior=json.loads(a.prior_report.read_text());jar=a.jar_path.resolve();jarbytes=jar.read_bytes()
sha=lambda b:hashlib.sha256(b).hexdigest()
if prior['jarSha256']!=sha(jarbytes):raise SystemExit('Original frozen execution artifact required')
schema=prior['postgresSchema']
if not re.fullmatch('raw_stream30_[0-9a-f]{12}',schema):raise SystemExit('Exact original isolated schema required')
for c in prior['cases']:
 if sha(Path(c['originalPath']).read_bytes())!=c['originalSha256']:raise SystemExit('Original fixture bytes changed')
settings=dict(line.split('=',1) for line in (ROOT/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
source=ROOT/'scripts/RawStreamThirtyProbe.java';sourcebytes=source.read_bytes();java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17']).decode().strip())/'bin'
a.output.parent.mkdir(parents=True,exist_ok=True);frozen=a.output.with_suffix('.frozen');frozen.mkdir();(frozen/source.name).write_bytes(sourcebytes)
report=dict(status='FAIL_ORIGINAL_STREAM30_RECOVERY_HARNESS',originalFailedReport=str(a.prior_report.resolve()),originalFailedReportSha256=sha(a.prior_report.read_bytes()),originalProbeSourceSha256=prior['probeSourceSha256'],jarSha256=sha(jarbytes),postgresSchema=schema,probeSourceSha256=sha(sourcebytes),migrationProvenance='ORIGINAL_SCHEMA_REUSED_NO_MIGRATIONS_OR_FIXTURES_APPLIED',originalMigrationSha256=prior['migrationSha256'])
try:
 with tempfile.TemporaryDirectory(prefix='evokb-stream30-recovery-') as temp:
  work=Path(temp);libs={}
  with zipfile.ZipFile(jar) as archive:
   for name in archive.namelist():
    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
     data=archive.read(name);(work/Path(name).name).write_bytes(data);libs[Path(name).name]=sha(data)
  report['dependencyJarSha256']=libs
  snapshot=work/source.name;snapshot.write_bytes(sourcebytes);cp=str(work/'*')+os.pathsep+str(work)
  compiled=subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(work),str(snapshot)],capture_output=True,text=True,timeout=40)
  a.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr);compiled.check_returncode()
  report['compiledProbeClassSha256']={x.name:sha(x.read_bytes()) for x in work.glob('RawStreamThirtyProbe*.class')}
  env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema='+schema+'&stringtype=unspecified')
  result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',cp,source.stem,str(a.output.resolve()),schema,'resume',str(a.prior_report.resolve())],env=env,capture_output=True,timeout=180)
  a.output.with_suffix('.runtime.log').write_bytes(result.stdout+result.stderr)
  partial=json.loads(a.output.read_text()) if a.output.exists() else {}
  report={**report,**partial};report['executionExitCode']=result.returncode
except Exception as error:
 partial=json.loads(a.output.read_text()) if a.output.exists() else {}
 report={**report,**partial};report.update(status='FAIL_ORIGINAL_STREAM30_RECOVERY_HARNESS',harnessFailure=type(error).__name__+': '+str(error))
report.update(observedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),currentJarStillMatchesLaunch=sha(jar.read_bytes())==sha(jarbytes),runnerSha256=sha(Path(__file__).read_bytes()))
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:report.get(k) for k in ['status','caseCount','executedCases','passedCases','developmentPassed','holdoutPassed','assertionCount','continuedExpiredCases','failure','harnessFailure']}));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
