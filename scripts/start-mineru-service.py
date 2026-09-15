"""Start only the local isolated parser; keep launch/log evidence and never restart a shared model."""
import argparse,hashlib,json,os,subprocess,sys,time,urllib.request
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--site',type=Path,default=R/'.tmp/mineru-office/site');p.add_argument('--workspace',type=Path,default=R/'.tmp/mineru-service');p.add_argument('--port',type=int,default=18486);p.add_argument('--stage-seconds',type=int,default=600);p.add_argument('--cpu-threads',type=int,choices=range(1,9),default=2);p.add_argument('--runtime-source',type=Path);p.add_argument('--hybrid-manifest',type=Path);p.add_argument('--device',choices=['cpu','mps']);a=p.parse_args();a.workspace=a.workspace.resolve();a.workspace.mkdir(parents=True,exist_ok=True)
if not 1<=a.stage_seconds<=600:raise SystemExit('Parser stage budget must be within 1..600 seconds')
if a.hybrid_manifest and sys.version_info<(3,12):raise SystemExit('Pinned native hybrid site requires the provisioned Python3.12 runtime; preserve current launch/ledger and use its saved args[0]')
url=f'http://127.0.0.1:{a.port}'
source_path=a.runtime_source.resolve() if a.runtime_source else R/'scripts/mineru-service/app.py'
if a.runtime_source and not source_path.is_relative_to(a.workspace/'runtime'):raise SystemExit('Explicit rollback runtime must be retained under this parser workspace/runtime')
source=source_path.read_bytes();source_hash=hashlib.sha256(source).hexdigest()
try:
 with urllib.request.urlopen(url+'/ready',timeout=1) as response:ready=json.load(response)
 if ready.get('protocol')=='evokb-mineru-isolated-v1':
  launch=a.workspace/'launch.json';old=json.loads(launch.read_text()) if launch.exists() else {}
  requested_manifest=str(a.hybrid_manifest.resolve()) if a.hybrid_manifest else None
  if old.get('site')!=str(a.site.resolve()) or old.get('hybridManifest')!=requested_manifest or old.get('device')!=(a.device or 'native-auto') or old.get('stageSeconds')!=a.stage_seconds or old.get('cpuThreads',2)!=a.cpu_threads or old.get('parserSourceSha256')!=source_hash:raise SystemExit('Existing parser configuration/source differs; preserve ledger and explicitly stop its exact PID before replacement')
  print(json.dumps(dict(status='ALREADY_RUNNING_MATCHING_CONFIGURATION',url=url,ready=ready)));raise SystemExit(0)
 raise SystemExit('Requested port is already used by another service')
except (OSError,TimeoutError):pass
env=os.environ.copy();env.update(EKB_MINERU_SITE=str(a.site.resolve()),EKB_MINERU_WORKSPACE=str(a.workspace),EKB_MINERU_STAGE_SECONDS=str(a.stage_seconds),EKB_MINERU_CPU_THREADS=str(a.cpu_threads),PYTHONPATH=str(a.site.resolve())+os.pathsep+env.get('PYTHONPATH',''),HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',OMP_NUM_THREADS=str(a.cpu_threads),MKL_NUM_THREADS=str(a.cpu_threads))
if a.hybrid_manifest:
 manifest=json.loads(a.hybrid_manifest.read_text())
 if manifest.get('parserVersion')!='3.4.5' or manifest.get('backend')!='hybrid-engine' or manifest.get('effort')!='medium' or manifest.get('parseMethod')!='auto' or not manifest.get('files'):raise SystemExit('Explicit pinned hybrid manifest does not match the agreed parser profile')
 for key in ['pipeline','vlm']:
  if not Path(manifest.get('models',{}).get(key,'')).is_dir():raise SystemExit('Local model root missing: '+key)
 config=a.workspace/'mineru.json';config.write_text(json.dumps({'config_version':'1.3.2','models-dir':manifest['models']},indent=2))
 env.update(EKB_MINERU_HYBRID_MANIFEST=str(a.hybrid_manifest.resolve()),MINERU_MODEL_SOURCE='local',MINERU_TOOLS_CONFIG_JSON=str(config),HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1')
if a.device:env['MINERU_DEVICE_MODE']=a.device
runtime=a.workspace/'runtime'/source_hash;runtime.mkdir(parents=True,exist_ok=True);frozen=runtime/'app.py'
if frozen.exists() and frozen.read_bytes()!=source:raise SystemExit('Frozen parser runtime content differs from its identity')
if not frozen.exists():frozen.write_bytes(source)
args=[sys.executable,'-m','uvicorn','--app-dir',str(runtime),'app:app','--host','127.0.0.1','--port',str(a.port)]
log=a.workspace/f'launch-{int(time.time())}.log'
with log.open('w') as sink:process=subprocess.Popen(args,cwd=R,env=env,stdout=sink,stderr=subprocess.STDOUT,start_new_session=True)
receipt=dict(createdAt=datetime.now(timezone.utc).isoformat(),pid=process.pid,url=url,args=args,workspace=str(a.workspace),site=str(a.site.resolve()),stageSeconds=a.stage_seconds,log=str(log),stopCommand=f'kill -TERM {process.pid}',boundary='Only this parser service and its owned native child; no shared model restart.')
receipt.update(hybridManifest=str(a.hybrid_manifest.resolve()) if a.hybrid_manifest else None,device=a.device or 'native-auto',cpuThreads=a.cpu_threads,runtimePythonVersion=sys.version,modelSource='local' if a.hybrid_manifest else None)
receipt.update(parserSourceSha256=source_hash,parserRuntime=str(frozen),launcherSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),hybridManifestSha256=hashlib.sha256(a.hybrid_manifest.read_bytes()).hexdigest() if a.hybrid_manifest else None)
(a.workspace/'launch.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
for _ in range(50):
 try:
  with urllib.request.urlopen(url+'/ready',timeout=1) as response:ready=json.load(response)
  if ready.get('parserSourceSha256')!=source_hash:raise SystemExit('Parser ready source differs from frozen launch identity')
  receipt['ready']=ready;(a.workspace/'launch.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2));print(json.dumps(receipt,ensure_ascii=False));raise SystemExit(0)
 except OSError:
  if process.poll() is not None:raise SystemExit(f'Parser launch failed; retained {log}')
  time.sleep(.1)
raise SystemExit(f'Parser startup not confirmed; retained {log}')
