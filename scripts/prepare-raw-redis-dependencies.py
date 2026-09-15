"""Fetch only missing Boot3.5.8-compatible native probe artifacts; official SHA512 check, no Maven build."""
import argparse,hashlib,json,urllib.request,urllib.error
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence required')
coords=[('org.springframework.data',x,'3.5.6') for x in ['spring-data-redis','spring-data-keyvalue','spring-data-commons']]+[('io.lettuce','lettuce-core','6.6.0.RELEASE')]+[('io.netty',x,'4.1.128.Final') for x in ['netty-common','netty-buffer','netty-transport','netty-resolver','netty-codec','netty-handler','netty-codec-dns','netty-resolver-dns','netty-transport-native-unix-common']]
records=[];base=Path.home()/'.m2/repository';a.output.write_text(json.dumps(dict(status='IN_PROGRESS',records=records),indent=2))
for group,artifact,version in coords:
 folder=base/group.replace('.','/')/artifact/version;folder.mkdir(parents=True,exist_ok=True)
 for ext in ['pom','jar']:
  filename=f'{artifact}-{version}.{ext}';url='https://repo.maven.apache.org/maven2/'+group.replace('.','/')+'/'+artifact+'/'+version+'/'+filename;target=folder/filename
  if target.exists():data=target.read_bytes();origin='existing-local-cache'
  else:
   with urllib.request.urlopen(url,timeout=20) as response:data=response.read()
   origin='official-central-download'
  expected=None
  for algorithm in ['sha512','sha256','sha1']:
   try:
    with urllib.request.urlopen(url+'.'+algorithm,timeout=20) as response:expected=response.read().decode().strip().split()[0]
    break
   except urllib.error.HTTPError as error:
    if error.code!=404:raise
  if expected is None or hashlib.new(algorithm,data).hexdigest()!=expected:raise RuntimeError('Official digest mismatch or unavailable '+filename)
  if not target.exists():target.write_bytes(data)
  records.append(dict(path=str(target),url=url,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),officialDigest=dict(algorithm=algorithm,value=expected),origin=origin))
 a.output.write_text(json.dumps(dict(status='IN_PROGRESS',records=records),indent=2))
a.output.write_text(json.dumps(dict(status='PASS_EXACT_BOM_ARTIFACTS',records=records,totalBytes=sum(r['bytes'] for r in records)),indent=2));print('PASS exact artifacts bytes',sum(r['bytes'] for r in records))
