"""Explicit controlled replacement of this project's parser only; preserve SQLite/workspace/old receipts."""
import argparse,json,os,signal,sqlite3,subprocess,sys,time,urllib.request
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[1];p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path,default=R/'.tmp/mineru-service');p.add_argument('--port',type=int,default=18486);p.add_argument('--site',type=Path,default=R/'.tmp/mineru-hybrid/site');p.add_argument('--manifest',type=Path,default=R/'.tmp/mineru-hybrid/models/manifest.json');p.add_argument('--cpu-threads',type=int,choices=range(1,9),default=2);p.add_argument('--device',choices=('cpu','mps'),default='cpu');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence required')
a.output.mkdir(parents=True);launch=a.workspace/'launch.json';old=json.loads(launch.read_text());(a.output/'previous-launch.json').write_text(json.dumps(old,indent=2));runtime_python=old['args'][0]
if not Path(runtime_python).is_file():raise SystemExit('Saved parser Python runtime is unavailable; do not stop the current parser')
pid=int(old['pid']);command=subprocess.check_output(['ps','-p',str(pid),'-o','lstart=,command='],text=True).strip()
if not all(x in command for x in ['uvicorn','--host 127.0.0.1',f'--port {a.port}']):raise SystemExit('Saved parser PID does not own the expected loopback service')
with sqlite3.connect('file:'+str((a.workspace/'tasks.sqlite3').resolve())+'?mode=ro',uri=True) as db:
 states=db.execute('SELECT status,count(*) FROM tasks GROUP BY status').fetchall()
 if db.execute("SELECT count(*) FROM tasks WHERE status IN ('running','queued')").fetchone()[0]:raise SystemExit('Parser has active originals; replacement postponed, nothing stopped')
with urllib.request.urlopen(f'http://127.0.0.1:{a.port}/ready',timeout=2) as response:ready=json.load(response)
actor=ready.get('workerPid');receipt=dict(status='RUNNING',startedAt=datetime.now(timezone.utc).isoformat(),oldCommand=command,previousReady=ready,taskCounts=states,oldPid=pid,oldWorkerPid=actor,workspace=str(a.workspace.resolve()),boundary='Only this EvoKB parser and its owned worker. SQLite, originals, images, dependency sites and models retained; no shared embedding-model changes.')
(a.output/'result.json').write_text(json.dumps(receipt,indent=2));os.kill(pid,signal.SIGTERM)
for _ in range(60):
 processes=subprocess.check_output(['ps','-axo','pid,pgid,stat,command'],text=True).splitlines()[1:];live=[row for row in processes if row.split()[0] in [str(pid),str(actor)] and not row.split()[2].startswith('Z')]
 if not live:break
 time.sleep(.2)
if live:receipt.update(status='FAIL_OWNED_RESOURCE_RELEASE',remainingOwned=live);(a.output/'result.json').write_text(json.dumps(receipt,indent=2));raise SystemExit('Owned parser shutdown not confirmed; do not launch another')
args=[runtime_python,str(R/'scripts/start-mineru-service.py'),'--site',str(a.site),'--workspace',str(a.workspace),'--port',str(a.port),'--stage-seconds','600','--cpu-threads',str(a.cpu_threads),'--hybrid-manifest',str(a.manifest),'--device',a.device];r=subprocess.run(args,capture_output=True,text=True,timeout=25);(a.output/'new-launch.log').write_text(r.stdout+r.stderr);receipt.update(newLaunchExitCode=r.returncode,ownedOldResourcesReleased=True,runtimePython=runtime_python)
restore=[runtime_python,str(R/'scripts/start-mineru-service.py'),'--site',old['site'],'--workspace',str(a.workspace),'--port',str(a.port),'--stage-seconds',str(old['stageSeconds']),'--cpu-threads',str(old.get('cpuThreads',2)),'--runtime-source',old['parserRuntime']]
if old.get('hybridManifest'):restore.extend(['--hybrid-manifest',old['hybridManifest']])
if old.get('device') in ('cpu','mps'):restore.extend(['--device',old['device']])
receipt['restoreCommand']=restore
receipt['restoreBoundary']='Wait for zero active parser tasks and stop only the current saved parser PID before running these old frozen runtime arguments; no ledger/original/model deletion.'
if r.returncode:receipt.update(status='FAIL_NEW_PARSER_START');(a.output/'result.json').write_text(json.dumps(receipt,indent=2));raise SystemExit('New parser failed; prior site/ledger retained and exact restore arguments saved')
new=json.loads(launch.read_text());(a.output/'new-launch.json').write_text(json.dumps(new,indent=2));receipt.update(status='PASS_FROZEN_HYBRID_DEPLOYMENT',newPid=new['pid'],newReady=new['ready'],newParserSourceSha256=new['parserSourceSha256'],newHybridManifestSha256=new['hybridManifestSha256'],finishedAt=datetime.now(timezone.utc).isoformat());(a.output/'result.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(dict(status=receipt['status'],newPid=receipt['newPid'],newReady=receipt['newReady'])))
