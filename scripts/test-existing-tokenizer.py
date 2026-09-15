"""Native DJL + existing serving preprocessor parity; no embedding/rerank inference."""
from native_probe_runtime import java17_bin
import argparse
from datetime import datetime,timezone
import hashlib,io,json,os
from pathlib import Path
import subprocess,tempfile,urllib.request,zipfile
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--tokenizer',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--serving',default='http://127.0.0.1:8110');a=p.parse_args()
if a.output.exists(): raise SystemExit('Use fresh output path; retain prior attempts')
a.output.parent.mkdir(parents=True,exist_ok=True)
texts=['原合同约定验收后30日付款，补充协议改为60日。','Payment is due only after a valid invoice; USD 9,000,000.00.','合同\r\n 空白  保留\t金额900万元 😀','<table><tr><th>金额（万元）</th><th>条件</th></tr><tr><td>900</td><td>验收且取得发票后付款</td></tr></table>','中文English_0123标点：；。😀'*60,'']
payload=dict(input=texts,input_type='document');ip=a.output.with_suffix('.inputs.json');ip.write_text(json.dumps(payload,ensure_ascii=False,indent=2));native=a.output.with_suffix('.native.json')
pinned=[Path.home()/'.m2/repository/org/jsoup/jsoup/1.21.2/jsoup-1.21.2.jar',Path.home()/'.m2/repository/ai/djl/huggingface/tokenizers/0.32.0/tokenizers-0.32.0.jar',Path.home()/'.m2/repository/ai/djl/api/0.32.0/api-0.32.0.jar']
jar=R/'java/ekb-bootstrap/target/evokb.jar'
jar_bytes=jar.read_bytes()
sources=[R/'scripts/RawTokenizerProbe.java',*(R/'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/raw'/name for name in ['LocalEmbeddingTokenizer.java','FrozenRawEncodingConfiguration.java']),*(R/'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw'/name for name in ['RawKnowledgeBuilder.java','RawTokenCounter.java','RawChunkProfile.java','RawEncodingProfile.java'])]
java=java17_bin()
with tempfile.TemporaryDirectory(prefix='evokb-native-token-') as d:
 deps=[]
 with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
  for entry in archive.namelist():
   if entry.startswith('BOOT-INF/classes/') and not entry.endswith('/'):
    target=Path(d)/'boot'/entry[len('BOOT-INF/classes/'):];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(entry))
   if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
    target=Path(d)/'lib'/Path(entry).name;target.parent.mkdir(exist_ok=True);target.write_bytes(archive.read(entry));deps.append(target)
 cp=os.pathsep.join([str(Path(d)/'boot'),*(str(x) for x in pinned),*(str(x) for x in deps)])
 compiled=subprocess.run([str(java/'javac'),'-cp',cp,'-d',d,*(str(s) for s in sources)],text=True,capture_output=True)
 a.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr)
 if compiled.returncode: raise SystemExit('Native probe compilation failed; see retained log')
 ran=subprocess.run([str(java/'java'),'-XX:ActiveProcessorCount=2','-Xmx256m','-cp',d+os.pathsep+cp,'cn.lgs.knowledgebase.application.raw.RawTokenizerProbe',str(a.tokenizer),str(ip),str(native)],text=True,capture_output=True,timeout=120)
 a.output.with_suffix('.native.log').write_text(ran.stdout+ran.stderr)
 if ran.returncode: raise SystemExit('Native tokenizer probe failed; see retained log')
try:
 with urllib.request.urlopen(urllib.request.Request(a.serving+'/v1/embedding-token-count',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'}),timeout=15) as response:
  remote=json.load(response)
except Exception as error:
 a.output.write_text(json.dumps(dict(status='BLOCKED_SERVING_TOKEN_COUNT',error=str(error),native=json.loads(native.read_text())),ensure_ascii=False,indent=2));raise SystemExit(1)
local=json.loads(native.read_text());checks=dict(local['checks']);checks['exactPreprocessorParity']=local['localTokenCounts']==remote['tokenCounts'];checks['servingNeverTruncated']=remote.get('truncated') is False
report=dict(status='PASS_NATIVE_TOKENIZER_PARITY_AND_STRUCTURE' if all(checks.values()) else 'FAIL_NATIVE_TOKENIZER_PARITY_AND_STRUCTURE',createdAt=datetime.now(timezone.utc).isoformat(),checks=checks,localTokenCounts=local['localTokenCounts'],actualServingReport=remote,sourceHashes={str(s.relative_to(R)):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},dependencyJarSha256=hashlib.sha256(jar_bytes).hexdigest(),pinnedDependencies={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in pinned},manifest=json.loads((a.tokenizer/'manifest.json').read_text()),boundary='Actual native DJL and loaded Qwen preprocessor parity. No embedding inference, model-quality or MinerU claim; source text and table chunk outputs retained separately.')
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=checks,local=local['localTokenCounts'],remote=remote['tokenCounts']),ensure_ascii=False));raise SystemExit(0 if all(checks.values()) else 1)
