"""Copy tokenizer assets from the existing local embedding cache; never fetch model weights."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--container',default='embedding-model');a=p.parse_args()
revision='9f2f7e710d6d81056aa5c0a4f04764fec6bb7bda'
root='/models/sentence-transformers/models--Qwen--Qwen3-VL-Embedding-2B/snapshots/'+revision
if a.output.exists() and any(a.output.iterdir()): raise SystemExit('Use a new export path; preserve previous evidence')
a.output.mkdir(parents=True,exist_ok=True)
files=['tokenizer.json','tokenizer_config.json','chat_template.jinja','config_sentence_transformers.json','sentence_bert_config.json','modules.json']
for name in files:
 subprocess.run(['docker','cp','-L',a.container+':'+root+'/'+name,str(a.output/name)],check=True,capture_output=True)
# Render the installed model's frozen Jinja template without importing torch or loading a model.
program='''import json, pathlib, jinja2
root=pathlib.Path(%s)
config=json.loads((root/'config_sentence_transformers.json').read_text())
prompt=config['prompts'][config['default_prompt_name']]
marker='EVOKB_TEXT_SLOT_070F35'
env=jinja2.Environment(undefined=jinja2.StrictUndefined)
template=env.from_string((root/'chat_template.jinja').read_text())
text=template.render(messages=[{'role':'system','content':[{'type':'text','text':prompt}]},{'role':'user','content':[{'type':'text','text':marker}]}],tools=None,add_generation_prompt=True,add_vision_id=False)
assert text.count(marker)==1
prefix,suffix=text.split(marker)
print(json.dumps({'prefix':prefix,'suffix':suffix,'instruction':prompt}))
'''%repr(root)
rendered=json.loads(subprocess.run(['docker','exec','-i',a.container,'python','-c',program],check=True,capture_output=True,text=True).stdout)
manifest=dict(createdAt=datetime.now(timezone.utc).isoformat(),model='Qwen/Qwen3-VL-Embedding-2B',modelRevision=revision,tokenizerRevision=revision,dimensions=1024,sourceContainer=a.container,sourcePath=root,files={name:hashlib.sha256((a.output/name).read_bytes()).hexdigest() for name in files},rendering=rendered,boundary='Copied existing assets only; rendering is frozen from actual Jinja. Exact preprocessing parity must be checked against serving token-count before use. No embedding inference or model download.')
(a.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
print(json.dumps(dict(output=str(a.output),revision=revision,totalBytes=sum(f.stat().st_size for f in a.output.iterdir()),fileCount=len(files),tokenizerSha256=manifest['files']['tokenizer.json'])))
