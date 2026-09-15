"""Start only the local isolated parser; keep launch/log evidence and never restart a shared model."""
import argparse,ast,hashlib,json,os,subprocess,sys,time,urllib.request
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1]

def runtime_sources(source_path):
 source=source_path.read_bytes();source_hash=hashlib.sha256(source).hexdigest()
 imports={node.module for node in ast.walk(ast.parse(source)) if isinstance(node,ast.ImportFrom)}
 imports.update(alias.name for node in ast.walk(ast.parse(source)) if isinstance(node,ast.Import) for alias in node.names)
 modules={}
 for name in sorted({'pdf_text_tables','native_attention'}.intersection(imports)):
  module_path=source_path.with_name(name+'.py')
  if not module_path.is_file():raise SystemExit('Parser runtime module missing: '+name)
  modules[module_path.name]=module_path.read_bytes()
 hashes={name:hashlib.sha256(data).hexdigest() for name,data in modules.items()}
 bundle=dict(appSha256=source_hash,modules=hashes)
 bundle['bundleSha256']=hashlib.sha256(json.dumps(bundle,sort_keys=True).encode()).hexdigest() if modules else source_hash
 return source,modules,bundle

def freeze_runtime(workspace,source,modules,bundle):
 runtime=workspace/'runtime'/bundle['bundleSha256'];runtime.mkdir(parents=True,exist_ok=True)
 files={'app.py':source,**modules}
 if modules:files['runtime-bundle.json']=json.dumps(bundle,sort_keys=True,indent=2).encode()
 for name,data in files.items():
  frozen=runtime/name
  if frozen.exists() and frozen.read_bytes()!=data:raise SystemExit('Frozen parser runtime content differs from its identity: '+name)
 # Validate every retained file before filling an incomplete first-time freeze.
 for name,data in files.items():
  frozen=runtime/name
  if not frozen.exists():frozen.write_bytes(data)
 return runtime

p=argparse.ArgumentParser();p.add_argument('--site',type=Path,default=R/'.tmp/mineru-office/site');p.add_argument('--workspace',type=Path,default=R/'.tmp/mineru-service');p.add_argument('--port',type=int,default=18486);p.add_argument('--stage-seconds',type=int,default=600);p.add_argument('--cpu-threads',type=int,choices=range(1,9),default=2);p.add_argument('--cpu-attention',choices=['native','expanded-kv','expanded-kv-fp32'],default='native');p.add_argument('--runtime-source',type=Path);p.add_argument('--hybrid-manifest',type=Path);p.add_argument('--device',choices=['cpu','mps']);a=p.parse_args();a.workspace=a.workspace.resolve();a.workspace.mkdir(parents=True,exist_ok=True)
if not 1<=a.stage_seconds<=600:raise SystemExit('Parser stage budget must be within 1..600 seconds')
if a.hybrid_manifest and sys.version_info<(3,12):raise SystemExit('Pinned native hybrid site requires the provisioned Python3.12 runtime; preserve current launch/ledger and use its saved args[0]')
url=f'http://127.0.0.1:{a.port}'
source_path=a.runtime_source.resolve() if a.runtime_source else R/'scripts/mineru-service/app.py'
if a.runtime_source and not source_path.is_relative_to(a.workspace/'runtime'):raise SystemExit('Explicit rollback runtime must be retained under this parser workspace/runtime')
source,module_bytes,bundle=runtime_sources(source_path)
source_hash=bundle['appSha256'];module_hashes=bundle['modules'];bundle_hash=bundle['bundleSha256']
try:
 with urllib.request.urlopen(url+'/ready',timeout=1) as response:ready=json.load(response)
 if ready.get('protocol')=='evokb-mineru-isolated-v1':
  launch=a.workspace/'launch.json';old=json.loads(launch.read_text()) if launch.exists() else {}
  requested_manifest=str(a.hybrid_manifest.resolve()) if a.hybrid_manifest else None
  if old.get('site')!=str(a.site.resolve()) or old.get('hybridManifest')!=requested_manifest or old.get('device')!=(a.device or 'native-auto') or old.get('stageSeconds')!=a.stage_seconds or old.get('cpuThreads',2)!=a.cpu_threads or old.get('cpuAttention','native')!=a.cpu_attention or old.get('parserSourceSha256')!=source_hash or old.get('parserRuntimeBundleSha256',source_hash)!=bundle_hash or old.get('parserRuntimeModulesSha256',{})!=module_hashes:raise SystemExit('Existing parser configuration/source differs; preserve ledger and explicitly stop its exact PID before replacement')
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
env['EKB_MINERU_CPU_ATTENTION']=a.cpu_attention
if a.device:env['MINERU_DEVICE_MODE']=a.device
runtime=freeze_runtime(a.workspace,source,module_bytes,bundle);frozen=runtime/'app.py'
args=[sys.executable,'-m','uvicorn','--app-dir',str(runtime),'app:app','--host','127.0.0.1','--port',str(a.port)]
log=a.workspace/f'launch-{int(time.time())}.log'
with log.open('w') as sink:process=subprocess.Popen(args,cwd=R,env=env,stdout=sink,stderr=subprocess.STDOUT,start_new_session=True)
receipt=dict(createdAt=datetime.now(timezone.utc).isoformat(),pid=process.pid,url=url,args=args,workspace=str(a.workspace),site=str(a.site.resolve()),stageSeconds=a.stage_seconds,log=str(log),stopCommand=f'kill -TERM {process.pid}',boundary='Only this parser service and its owned native child; no shared model restart.')
receipt.update(hybridManifest=str(a.hybrid_manifest.resolve()) if a.hybrid_manifest else None,device=a.device or 'native-auto',cpuThreads=a.cpu_threads,cpuAttention=a.cpu_attention,runtimePythonVersion=sys.version,modelSource='local' if a.hybrid_manifest else None)
receipt.update(parserSourceSha256=source_hash,parserRuntime=str(frozen),launcherSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),hybridManifestSha256=hashlib.sha256(a.hybrid_manifest.read_bytes()).hexdigest() if a.hybrid_manifest else None)
receipt.update(parserRuntimeBundleSha256=bundle_hash,parserRuntimeModulesSha256=module_hashes)
(a.workspace/'launch.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
for _ in range(50):
 try:
  with urllib.request.urlopen(url+'/ready',timeout=1) as response:ready=json.load(response)
  if ready.get('parserSourceSha256')!=source_hash:raise SystemExit('Parser ready source differs from frozen launch identity')
  if module_bytes and (ready.get('parserRuntimeBundleSha256')!=bundle_hash or ready.get('parserRuntimeModulesSha256')!=module_hashes):raise SystemExit('Parser ready modules differ from frozen launch identity')
  receipt['ready']=ready;(a.workspace/'launch.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2));print(json.dumps(receipt,ensure_ascii=False));raise SystemExit(0)
 except OSError:
  if process.poll() is not None:raise SystemExit(f'Parser launch failed; retained {log}')
  time.sleep(.1)
raise SystemExit(f'Parser startup not confirmed; retained {log}')
