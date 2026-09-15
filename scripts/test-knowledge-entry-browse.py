#!/usr/bin/env python3
"""Freeze and compile the new read API, then test authority against retained real PG fixtures."""
import argparse,hashlib,json,os,subprocess,zipfile
from pathlib import Path
from postgres_component_probe import run

R=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--jar-path',type=Path,required=True)
a=parser.parse_args()
if a.output.exists():raise SystemExit('Fresh report path required')
frozen=a.output.with_suffix('.frozen');frozen.mkdir(parents=True,exist_ok=False)
libs=frozen/'libs';libs.mkdir();classes=frozen/'classes';classes.mkdir()
manifest={};jar=a.jar_path.resolve();jarBytes=jar.read_bytes()
with zipfile.ZipFile(jar) as z:
 for name in z.namelist():
  if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(libs/Path(name).name).write_bytes(z.read(name))
paths=[
 'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/operations/EnterpriseOperations.java',
 'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcEnterpriseOperations.java',
 'java/ekb-interfaces/src/main/java/cn/lgs/knowledgebase/interfaces/http/EnterpriseApiController.java',
]
sources=[]
for path in paths:
 original=R/path;target=frozen/'sources'/path;target.parent.mkdir(parents=True,exist_ok=True);data=original.read_bytes();target.write_bytes(data);manifest[path]=hashlib.sha256(data).hexdigest();sources.append(str(target))
java=Path(subprocess.check_output(['/usr/libexec/java_home','-v','17']).decode().strip())/'bin'
result=subprocess.run([str(java/'javac'),'-J-Xmx256m','-J-XX:ActiveProcessorCount=2','-cp',str(libs/'*'),'-d',str(classes),*sources],capture_output=True,text=True)
a.output.with_suffix('.source-compile.log').write_text(result.stdout+result.stderr)
if result.returncode:
 a.output.write_text(json.dumps({'status':'FAIL_ENTRY_READ_SOURCE_COMPILATION','code':result.returncode,'sourceHashes':manifest,'jarSha256':hashlib.sha256(jarBytes).hexdigest()},indent=2));print(result.stderr);raise SystemExit(result.returncode)
code=run(a.output,source_name='KnowledgeEntryBrowseProbe.java',prefix='entry_browse_',jar_path=jar,classes_path=classes,timeout=60)
report=json.loads(a.output.read_text());report['productionSourceHashes']=manifest
report['productionSourcesStableSinceFrozen']=all(hashlib.sha256((R/p).read_bytes()).hexdigest()==v for p,v in manifest.items())
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
raise SystemExit(code)
