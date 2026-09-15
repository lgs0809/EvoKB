"""Pinned necessary MinerU3.4.5 hybrid assets; streaming/resume, no whole ExtractKit or model load."""
import argparse,concurrent.futures,hashlib,json,os,threading,time,urllib.error,urllib.parse,urllib.request
from datetime import datetime,timezone
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--inventory',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();inventory=json.loads(a.inventory.read_text());a.output.mkdir(parents=True,exist_ok=True);a.evidence.parent.mkdir(parents=True,exist_ok=True)
if a.evidence.exists():raise SystemExit('Fresh evidence result path required')
selected=[]
for kind,repo,files in [('vlm','opendatalab/MinerU2.5-Pro-2605-1.2B',inventory['opendatalab/MinerU2.5-Pro-2605-1.2B']['siblings']),('pipeline','opendatalab/PDF-Extract-Kit-1.0',inventory['necessaryHybridMediumChinese']['selectedPipelineFiles'])]:
 revision=inventory[repo]['sha']
 for file in files:selected.append(dict(kind=kind,repo=repo,revision=revision,filename=file['rfilename'],size=file['size'],expectedSha256=file.get('lfs',{}).get('sha256'),gitBlobId=file['blobId']))
lock=threading.Lock();completed=[];events=[]
def progress(event):
 with lock:
  events.append(dict(at=datetime.now(timezone.utc).isoformat(),**event));a.evidence.with_suffix('.progress.json').write_text(json.dumps(dict(completed=completed,events=events),ensure_ascii=False,indent=2))
def verify(path,file):
 sha=hashlib.sha256();git=hashlib.sha1();git.update(f'blob {file["size"]}\0'.encode())
 with path.open('rb') as stream:
  for chunk in iter(lambda:stream.read(1024*1024),b''):sha.update(chunk);git.update(chunk)
 if path.stat().st_size!=file['size']:raise ValueError('Asset size mismatch')
 if file['expectedSha256'] and sha.hexdigest()!=file['expectedSha256']:raise ValueError('LFS SHA256 mismatch')
 if not file['expectedSha256'] and git.hexdigest()!=file['gitBlobId']:raise ValueError('Frozen revision Git blob mismatch')
 return sha.hexdigest()
def download(file):
 target=a.output/file['kind']/file['filename'];target.parent.mkdir(parents=True,exist_ok=True)
 if target.exists():
  digest=verify(target,file);row=dict(**file,path=str(target.resolve()),sha256=digest,reusedExisting=True)
  with lock:completed.append(row)
  progress(dict(event='REUSED_VERIFIED_ASSET',filename=file['filename']));return row
 partial=target.with_name(target.name+'.part');url=f'https://huggingface.co/{file["repo"]}/resolve/{file["revision"]}/'+urllib.parse.quote(file['filename'],safe='/')
 for attempt in range(1,6):
  try:
   offset=partial.stat().st_size if partial.exists() else 0
   if offset==file['size']:digest=verify(partial,file)
   else:
    request=urllib.request.Request(url,headers={'Range':f'bytes={offset}-'} if offset else {})
    with urllib.request.urlopen(request,timeout=30) as response:
     resume=offset>0 and response.status==206
     with partial.open('ab' if resume else 'wb') as output:
      copied=offset if resume else 0;last=time.monotonic()
      while chunk:=response.read(1024*1024):
       output.write(chunk);copied+=len(chunk)
       if time.monotonic()-last>15:progress(dict(event='DOWNLOADING',filename=file['filename'],attempt=attempt,bytes=copied,total=file['size']));last=time.monotonic()
    digest=verify(partial,file)
   partial.replace(target);row=dict(**file,path=str(target.resolve()),sha256=digest,reusedExisting=False)
   with lock:completed.append(row)
   progress(dict(event='VERIFIED_ASSET',filename=file['filename'],bytes=file['size']));return row
  except Exception as error:
   progress(dict(event='DOWNLOAD_ATTEMPT_FAILED',filename=file['filename'],attempt=attempt,errorType=type(error).__name__,error=str(error) if not isinstance(error,urllib.error.HTTPError) else f'HTTP status {error.code}'))
   if attempt==5:raise
   time.sleep(min(2**attempt,16))
report=dict(createdAt=datetime.now(timezone.utc).isoformat(),status='FAIL',parserVersion='3.4.5',backend='hybrid-engine',effort='medium',parseMethod='auto',language='ch',modelBytesExpected=sum(f['size'] for f in selected),boundary='Only pinned selected assets, two streaming download threads, no model loading/inference and no shared embedding container changes')
try:
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:files=list(executor.map(download,selected))
 fingerprint=hashlib.sha256(json.dumps([(f['repo'],f['revision'],f['filename'],f['sha256']) for f in files],separators=(',',':')).encode()).hexdigest()
 manifest=dict(parserVersion='3.4.5',backend='hybrid-engine',effort='medium',parseMethod='auto',language='ch',modelRevision=fingerprint,models={'vlm':str((a.output/'vlm').resolve()),'pipeline':str((a.output/'pipeline').resolve())},repoRevisions={repo:inventory[repo]['sha'] for repo in ['opendatalab/MinerU2.5-Pro-2605-1.2B','opendatalab/PDF-Extract-Kit-1.0']},files=[dict(path=f['path'],sha256=f['sha256'],size=f['size'],sourceRepo=f['repo'],sourceRevision=f['revision'],sourceFile=f['filename']) for f in files])
 (a.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2));report.update(status='PASS_PINNED_MINERU_ASSETS_DOWNLOADED',files=files,manifestPath=str((a.output/'manifest.json').resolve()),manifestSha256=hashlib.sha256((a.output/'manifest.json').read_bytes()).hexdigest(),actualModelBytes=sum(Path(f['path']).stat().st_size for f in files))
except Exception as error:report['error']=type(error).__name__+': '+str(error)
report.update(events=events,verifiedFiles=completed,inventorySha256=hashlib.sha256(a.inventory.read_bytes()).hexdigest(),scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest());a.evidence.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({key:report[key] for key in ['status','modelBytesExpected']},ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
