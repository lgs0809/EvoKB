"""EvoKB's isolated MinerU3.4.5 protocol, distinct from upstream fast_api.

Only this service's persistent worker process is terminated for deadline/cancellation.
Native Office works without vision weights. PDF hybrid needs explicitly preprovisioned
local dependencies/model manifest; it cannot auto-download or silently switch backend.
"""
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from datetime import datetime,timezone
import hashlib,json,multiprocessing,os,signal,sqlite3,subprocess,threading,time,traceback,uuid
from pathlib import Path
from typing import Annotated
from fastapi import FastAPI,File,Form,HTTPException,UploadFile
from fastapi.responses import FileResponse

VERSION='3.4.5';PROTOCOL='evokb-mineru-isolated-v1'
WORKSPACE=Path(os.environ.get('EKB_MINERU_WORKSPACE','.tmp/mineru-service')).resolve()
STAGE_SECONDS=min(600,max(1,int(os.environ.get('EKB_MINERU_STAGE_SECONDS','600'))))
CPU_THREADS=int(os.environ.get('EKB_MINERU_CPU_THREADS','2'))
if not 1<=CPU_THREADS<=8:raise ValueError('EKB_MINERU_CPU_THREADS must be1..8')
SITE=os.environ.get('EKB_MINERU_SITE','')
MANAGER=None

def now():return datetime.now(timezone.utc).isoformat()
def sha256(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  for part in iter(lambda:stream.read(1024*1024),b''):h.update(part)
 return h.hexdigest()

class ParserFailure(Exception):
 def __init__(self,code,message):super().__init__(message);self.code=code

class Writer:
 def __init__(self,root):self.root=root.resolve()
 def write(self,path,data):
  target=(self.root/path).resolve()
  if not target.is_relative_to(self.root):raise ParserFailure('PARSER_OUTPUT_INVALID','Unsafe image path')
  target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)


def parse_native(job):
 from mineru.version import __version__
 if __version__!=VERSION:raise ParserFailure('PARSER_VERSION_MISMATCH',f'Expected {VERSION}; actual {__version__}')
 root=Path(job['workspace']);original=root/'original';suffix=Path(job['filename']).suffix.lower()
 began=time.monotonic();stages=[]
 def mark(stage,**facts):
  stages.append(dict(stage=stage,elapsedSeconds=round(time.monotonic()-began,4),observedAt=now(),**facts))
  (root/'runtime-stages.json').write_text(json.dumps(dict(cpuThreads=CPU_THREADS,parserSourceSha256=sha256(Path(__file__)),stages=stages),indent=2))
 mark('native-input')
 images=root/'images';images.mkdir(exist_ok=True)
 effective='office';revision='native-office-mineru-3.4.5'
 if suffix in ('.docx','.xlsx','.pptx'):
  if suffix=='.docx':from mineru.backend.office.docx_analyze import office_docx_analyze as analyze
  elif suffix=='.xlsx':from mineru.backend.office.xlsx_analyze import office_xlsx_analyze as analyze
  else:from mineru.backend.office.pptx_analyze import office_pptx_analyze as analyze
  from mineru.backend.office.office_middle_json_mkcontent import union_make
  from mineru.utils.enum_class import MakeMode
  middle,model=analyze(original.read_bytes(),image_writer=Writer(images))
  content=union_make(middle['pdf_info'],MakeMode.CONTENT_LIST,'images')
  markdown=union_make(middle['pdf_info'],MakeMode.MM_MD,'images')
 elif suffix=='.pdf':
  try:
   import pypdfium2
   pdf=pypdfium2.PdfDocument(original);pages=len(pdf);pdf.close()
  except Exception as error:raise ParserFailure('INPUT_ERROR',f'Unreadable/encrypted PDF: {error}') from error
  manifest_path=os.environ.get('EKB_MINERU_HYBRID_MANIFEST','')
  if not manifest_path:raise ParserFailure('DEPENDENCY_NOT_AVAILABLE','PDF hybrid requires an explicit local parser model manifest; no model download or backend fallback')
  manifest=json.loads(Path(manifest_path).read_text());revision=manifest.get('modelRevision','')
  if not revision or not manifest.get('files'):raise ParserFailure('PARSER_PROFILE_INVALID','Parser model revision/assets missing')
  if manifest.get('parserVersion')!=VERSION or any(manifest.get(key)!=job[value] for key,value in [('backend','backend'),('effort','effort'),('parseMethod','parseMethod'),('language','language')]):raise ParserFailure('PARSER_PROFILE_INVALID','Requested parser profile differs from pinned local model manifest')
  if os.environ.get('MINERU_MODEL_SOURCE')!='local' or not os.environ.get('MINERU_TOOLS_CONFIG_JSON'):raise ParserFailure('PARSER_PROFILE_INVALID','Local model source/config must be explicitly set before native parser import')
  for item in manifest['files']:
   path=Path(item['path'])
   if not path.is_file() or path.stat().st_size!=item['size'] or sha256(path)!=item['sha256']:raise ParserFailure('PARSER_PROFILE_INVALID','Local parser model asset size/checksum mismatch')
  mark('pinned-assets-verified')
  os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1'
  import torch
  torch.set_num_threads(CPU_THREADS)
  from mineru.utils.config_reader import get_device
  device=get_device()
  if device=='mps' and not torch.backends.mps.is_available():raise ParserFailure('DEPENDENCY_NOT_AVAILABLE','Requested native MPS device is unavailable')
  mark('native-cpu-configured',torchVersion=torch.__version__,actualTorchThreads=torch.get_num_threads(),nativeDevice=device,mpsBuilt=torch.backends.mps.is_built(),mpsAvailable=torch.backends.mps.is_available(),nativeMpsCpuFallback=os.environ.get('PYTORCH_ENABLE_MPS_FALLBACK','0'))
  try:
   from mineru.cli.common import do_parse
  except ImportError as error:raise ParserFailure('DEPENDENCY_NOT_AVAILABLE',f'PDF hybrid dependencies absent: {error}') from error
  except Exception as error:raise ParserFailure('INPUT_ERROR',f'Unreadable/encrypted PDF: {error}') from error
  effective=job['backend'];output=root/'native-output'
  mark('native-inference-started',pages=pages)
  do_parse(str(output),['source'],[original.read_bytes()],[job['language']],backend=job['backend'],effort=job['effort'],parse_method=job['parseMethod'],f_draw_layout_bbox=False,f_draw_span_bbox=False,f_dump_model_output=True)
  from mineru.backend.vlm.vlm_analyze import ModelSingleton
  model_devices=[]
  for predictor in ModelSingleton._models.values():
   native_model=getattr(predictor,'_mineru_runtime_handles',{}).get('model')
   if native_model is not None:model_devices.append(dict(modelType=type(native_model).__name__,device=str(native_model.device),dtype=str(native_model.dtype)))
  mark('native-inference-completed',actualNativeModelDevices=model_devices)
  middles=list(output.rglob('source_middle.json'));contents=list(output.rglob('source_content_list.json'));mds=list(output.rglob('source.md'))
  if len(middles)!=1 or len(contents)!=1 or len(mds)!=1:raise ParserFailure('QUALITY_REVIEW_REQUIRED','Ambiguous/missing native parser output')
  middle=json.loads(middles[0].read_text());content=json.loads(contents[0].read_text());markdown=mds[0].read_text();model={}
  if sorted(page.get('page_idx',-1) for page in middle.get('pdf_info',[]))!=list(range(pages)):
   raise ParserFailure('QUALITY_REVIEW_REQUIRED','Parser output does not cover every source PDF page')
  if any(not page.get('para_blocks') for page in middle.get('pdf_info',[])):
   raise ParserFailure('QUALITY_REVIEW_REQUIRED','At least one PDF page has no readable region; review input/parse quality')
  # Preserve native output paths and bytes, rather than rendering a replacement.
  import shutil
  nativeimages=middles[0].parent/'images'
  if nativeimages.exists():shutil.copytree(nativeimages,images,dirs_exist_ok=True)
 else:raise ParserFailure('INPUT_ERROR','Unsupported binary document format')
 if not any(block.get('text','').strip() or block.get('table_body','').strip() for block in content):
  raise ParserFailure('QUALITY_REVIEW_REQUIRED','No retrievable text/table found')
 for filename,data in [('middle.json',middle),('content-list-v1.json',content),('native-model-output.json',model)]:
  (root/filename).write_text(json.dumps(data,ensure_ascii=False,indent=2,default=lambda value:value.model_dump() if hasattr(value,'model_dump') else str(value)))
 (root/'document.md').write_text(markdown)
 image_entries={}
 for image in images.rglob('*'):
  if image.is_file():image_entries['images/'+image.relative_to(images).as_posix()]={'sha256':sha256(image),'size':image.stat().st_size,'path':image.relative_to(images).as_posix()}
 mark('native-artifacts-saved')
 return dict(version=VERSION,protocol=PROTOCOL,backend=job['backend'],effective_backend=effective,parser_profile=dict(backend=job['backend'],effectiveBackend=effective,effort=job['effort'],parseMethod=job['parseMethod'],language=job['language'],parserVersion=VERSION,modelRevision=revision,sourceSha256=sha256(original),positionSpace='office-element-order' if effective=='office' else 'normalized_1000',modelSource='native-office' if effective=='office' else 'local',device=os.environ.get('MINERU_DEVICE_MODE','native-auto'),cpuThreads=CPU_THREADS,runtimeSourceSha256=sha256(Path(__file__))),quality=dict(status='PASS',boundary='Native parse and structural coverage; independent business-field correctness is not implied'),results={job['filename']:dict(md_content=markdown,middle_json=middle,content_list=content,images=image_entries)})


def actor(pipe):
 # Native PDF rendering owns an official ProcessPoolExecutor. A daemon process cannot spawn it.
 # An isolated session lets cancellation terminate this owned parser tree, never other services.
 if hasattr(os,'setsid'):os.setsid()
 if SITE:
  import sys;sys.path.insert(0,SITE)
 # Offline by default, even before any parser module is imported.
 os.environ['HF_HUB_OFFLINE']='1';os.environ['TRANSFORMERS_OFFLINE']='1';os.environ['OMP_NUM_THREADS']=str(CPU_THREADS);os.environ['MKL_NUM_THREADS']=str(CPU_THREADS)
 if os.environ.get('MINERU_DEVICE_MODE')=='mps':os.environ['PYTORCH_ENABLE_MPS_FALLBACK']='1'
 while True:
  try:job=pipe.recv()
  except EOFError:return
  if job is None:return
  try:pipe.send(dict(ok=True,result=parse_native(job)))
  except Exception as error:
   code=getattr(error,'code','INPUT_ERROR' if isinstance(error,(ValueError,KeyError,UnicodeError)) or type(error).__name__ in ('BadZipFile','InvalidFileException') else 'PARSER_FAILED')
   Path(job['workspace'],'failure.log').write_text(traceback.format_exc())
   pipe.send(dict(ok=False,error=dict(code=code,message=str(error))))

class Manager:
 def __init__(self):
  WORKSPACE.mkdir(parents=True,exist_ok=True);self.db=WORKSPACE/'tasks.sqlite3';self.executor=ThreadPoolExecutor(max_workers=1,thread_name_prefix='mineru-stage');self.lock=threading.RLock();self.worker=None;self.pipe=None;self.running=None;self.cancel_events={};self.released={};self.closing=False
  with self.connect() as db:
   db.execute('CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,filename TEXT NOT NULL,status TEXT NOT NULL,config TEXT NOT NULL,error TEXT,result TEXT,created_at TEXT NOT NULL,started_at TEXT,finished_at TEXT,worker_pid INTEGER)')
   db.execute("UPDATE tasks SET status='failed',error=?,finished_at=? WHERE status='running'",(json.dumps(dict(code='PARSER_PROCESS_INTERRUPTED',message='Service restarted while native worker was running; original/task artifacts retained')),now()))
   queued=[row['id'] for row in db.execute("SELECT id FROM tasks WHERE status='queued' ORDER BY created_at")]
  for task_id in queued:self.executor.submit(self.run,task_id)
 def connect(self):
  connection=sqlite3.connect(self.db,timeout=5);connection.row_factory=sqlite3.Row;return connection
 def read(self,task_id):
  with self.connect() as db:row=db.execute('SELECT * FROM tasks WHERE id=?',(task_id,)).fetchone()
  if not row:raise HTTPException(404,'Task not found')
  return dict(row)
 def submit(self,filename,config):
  task_id=str(uuid.uuid4());root=WORKSPACE/task_id;root.mkdir();return task_id,root
 def enqueue(self,task_id,filename,config):
  with self.connect() as db:db.execute('INSERT INTO tasks(id,filename,status,config,created_at) VALUES(?,?,?,?,?)',(task_id,filename,'queued',json.dumps(config),now()))
  self.executor.submit(self.run,task_id)
 def start_actor(self):
  with self.lock:
   if self.worker is not None and self.worker.is_alive():return
   parent,child=multiprocessing.get_context('spawn').Pipe();self.worker=multiprocessing.get_context('spawn').Process(target=actor,args=(child,),name='evokb-mineru-native',daemon=False);self.worker.start();child.close();self.pipe=parent
 def stop_actor(self):
  with self.lock:
   if self.worker:
    pid=self.worker.pid;owned_group=False
    def live_group():
     rows=subprocess.check_output(['ps','-axo','pid,pgid,stat'],text=True).splitlines()[1:]
     return [row for row in rows if len(row.split())>=3 and row.split()[1]==str(pid) and not row.split()[2].startswith('Z')]
    def signal_group(value):
     # macOS can return EPERM when only an already-released/zombie group remains.
     # Never infer release from that errno alone; verify the exact owned group.
     if not live_group():return
     try:os.killpg(pid,value)
     except ProcessLookupError:pass
     except PermissionError:
      if live_group():raise
    try:owned_group=hasattr(os,'killpg') and os.getpgid(pid)==pid
    except ProcessLookupError:pass
    if owned_group:
     signal_group(signal.SIGTERM)
    else:self.worker.terminate()
    self.worker.join(2)
    if owned_group:
     # Reap any remaining native render children as well as the actor; never stop another group.
     signal_group(signal.SIGKILL)
    elif self.worker.is_alive():self.worker.kill()
    self.worker.join(2)
    if self.worker.is_alive():raise RuntimeError('Owned native worker termination unconfirmed')
    if owned_group:
     # Actor exit alone is insufficient: native rendering may own non-daemon grandchildren.
     until=time.monotonic()+2
     while True:
      live=live_group()
      if not live:break
      if time.monotonic()>=until:raise RuntimeError('Owned native process group release unconfirmed')
      time.sleep(.05)
   if self.pipe:self.pipe.close()
   self.worker=None;self.pipe=None
 def finish(self,task_id,status,error=None,result=None):
  with self.connect() as db:db.execute("UPDATE tasks SET status=?,error=?,result=?,finished_at=? WHERE id=? AND status='running'",(status,json.dumps(error) if error else None,json.dumps(result,ensure_ascii=False) if result else None,now(),task_id))
 def run(self,task_id):
  row=self.read(task_id);event=threading.Event();released=threading.Event()
  with self.lock:
   self.cancel_events[task_id]=event;self.released[task_id]=released
   if self.closing or self.read(task_id)['status']!='queued':released.set();return
   self.running=task_id
  try:
   self.start_actor()
   with self.connect() as db:
    changed=db.execute("UPDATE tasks SET status='running',started_at=?,worker_pid=? WHERE id=? AND status='queued'",(now(),self.worker.pid,task_id)).rowcount
   if not changed:return
   started=time.monotonic();job=json.loads(row['config']);job.update(filename=row['filename'],workspace=str(WORKSPACE/task_id));self.pipe.send(job)
   while True:
    if event.is_set() or self.closing:
     self.stop_actor();return
    if time.monotonic()-started>=STAGE_SECONDS:
     self.stop_actor();self.finish(task_id,'failed',dict(code='PARSER_STAGE_TIMEOUT',message=f'Native parser exceeded {STAGE_SECONDS}s; owned process terminated'));return
    if self.pipe.poll(.05):
     reply=self.pipe.recv()
     if reply['ok']:self.finish(task_id,'completed',result=reply['result'])
     else:self.finish(task_id,'failed',reply['error'])
     return
    if not self.worker.is_alive():self.finish(task_id,'failed',dict(code='PARSER_WORKER_LOST',message='Owned native parser process exited'));self.stop_actor();return
  except Exception as error:
   self.finish(task_id,'failed',dict(code='PARSER_WORKER_LOST',message=str(error)));self.stop_actor()
  finally:
   with self.lock:self.running=None
   released.set()
 def cancel(self,task_id):
  row=self.read(task_id)
  with self.connect() as db:
   changed=db.execute("UPDATE tasks SET status='cancelled',error=?,finished_at=? WHERE id=? AND status IN ('queued','running')",(json.dumps(dict(code='PARSER_CANCELLED',message='Cancellation requested')),now(),task_id)).rowcount
  if changed:
   with self.lock:
    event=self.cancel_events.get(task_id);released=self.released.get(task_id)
    if event:event.set()
  # Repeated cancellation must also wait if the first caller has only recorded the intent.
  with self.lock:
   still_owned=self.running==task_id;released=self.released.get(task_id)
  if still_owned and (not released or not released.wait(5)):raise HTTPException(503,'Cancellation recorded; owned native process group release is not yet confirmed')
  return dict(task_id=task_id,status=self.read(task_id)['status'],resource_release_confirmed=(not still_owned or bool(released and released.is_set())))
 def close(self):
  self.closing=True
  with self.lock:
   if self.running:self.cancel_events[self.running].set()
  self.executor.shutdown(wait=True,cancel_futures=True);self.stop_actor()

@asynccontextmanager
async def lifespan(app):
 global MANAGER
 MANAGER=Manager()
 try:yield
 finally:MANAGER.close();MANAGER=None

app=FastAPI(lifespan=lifespan)

@app.get('/ready')
def ready():
 return dict(status='ready',version=VERSION,protocol=PROTOCOL,stageSeconds=STAGE_SECONDS,cpuThreads=CPU_THREADS,office='native',pdfHybrid='explicit-local-manifest' if os.environ.get('EKB_MINERU_HYBRID_MANIFEST') else 'DEPENDENCY_NOT_AVAILABLE',workerPid=MANAGER.worker.pid if MANAGER.worker else None,parserSourceSha256=sha256(Path(__file__)),effectiveSite=SITE,hybridManifest=os.environ.get('EKB_MINERU_HYBRID_MANIFEST') or None)

@app.post('/tasks',status_code=202)
async def submit(files:Annotated[list[UploadFile],File()],backend:Annotated[str,Form()]='hybrid-engine',effort:Annotated[str,Form()]='medium',parse_method:Annotated[str,Form()]='auto',lang_list:Annotated[str,Form()]='ch'):
 if len(files)!=1:raise HTTPException(400,'Exactly one original per task required')
 if backend!='hybrid-engine' or effort!='medium' or parse_method!='auto' or lang_list!='ch':raise HTTPException(400,'Invalid frozen parser profile; expected hybrid-engine/medium/auto/ch')
 filename=Path(files[0].filename or 'original').name
 task_id,root=MANAGER.submit(filename,{});size=0
 with (root/'original').open('wb') as stream:
  while part:=await files[0].read(1024*1024):
   size+=len(part)
   if size>256*1024*1024:raise HTTPException(413,'Original exceeds isolated parser limit')
   stream.write(part)
 if not size:raise HTTPException(400,'Original is empty')
 MANAGER.enqueue(task_id,filename,dict(backend=backend,effort=effort,parseMethod=parse_method,language=lang_list))
 return dict(task_id=task_id,status='queued')

@app.get('/tasks/{task_id}')
def status(task_id:str):
 row=MANAGER.read(task_id)
 return dict(task_id=task_id,status=row['status'],error=json.loads(row['error']) if row['error'] else None,worker_pid=row['worker_pid'],created_at=row['created_at'],started_at=row['started_at'],finished_at=row['finished_at'])

@app.get('/tasks/{task_id}/result')
def result(task_id:str):
 row=MANAGER.read(task_id)
 if row['status']!='completed':raise HTTPException(409,'Task has no completed parser result')
 return json.loads(row['result'])

@app.delete('/tasks/{task_id}')
def cancel(task_id:str):return MANAGER.cancel(task_id)

@app.get('/tasks/{task_id}/images/{filename:path}')
def image(task_id:str,filename:str):
 row=MANAGER.read(task_id)
 if row['status']!='completed':raise HTTPException(409,'No completed image result')
 images=(WORKSPACE/task_id/'images').resolve();target=(images/filename).resolve()
 if not target.is_relative_to(images) or not target.is_file():raise HTTPException(404,'Image not found')
 return FileResponse(target)
