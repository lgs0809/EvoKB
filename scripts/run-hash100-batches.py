"""Retained 20 x 5 frozen-DEV batch coordinator. Preparation never calls a model.

Every attempt gets a fresh output. A paused/faulted batch resumes its own original
native checkpoint/schema/file and normal600s/5-attempt repository; completed
batches are skipped. This coordinator cannot bypass a business task quarantine.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import fcntl
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid

R = Path(__file__).resolve().parents[1]

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path, body):
    temp = Path(str(path) + '.pending')
    temp.write_text(json.dumps(body, ensure_ascii=False, indent=2))
    temp.replace(path)

def prepare(plan, corpus, jar, tokenizer):
    if plan.get('status') != 'PASS_FROZEN_ACTUAL_HASH_INPUTS_NO_INFERENCE' or plan['jarSha256'] != sha(jar):
        raise ValueError('Frozen native input plan and exact immutable artifact required')
    manifest = json.loads((corpus/'manifest.json').read_text())
    if plan['manifestSha256'] != sha(corpus/'manifest.json') or len(plan['documents']) != 100:
        raise ValueError('Frozen 100-document plan/corpus mismatch')
    docs = manifest['efficiency']['documents']
    if [d['id'] for d in docs] != [d['documentId'] for d in plan['documents']]:
        raise ValueError('Frozen development document order mismatch')
    for doc in docs:
        for original in [doc['baseline']] + [v['original'] for v in doc['versions']]:
            source = corpus/original['path']
            if source.stat().st_size != original['sizeBytes'] or sha(source) != original['sha256']:
                raise ValueError('Frozen original changed: '+doc['id'])
    assets = json.loads(tokenizer.read_text())['files']
    if any(sha(tokenizer.parent/name) != digest for name,digest in assets.items()):
        raise ValueError('Tokenizer assets differ')
    batches = []
    for index in range(0, 100, 5):
        local = plan['documents'][index:index+5]
        full_count=hash_count=full_tokens=hash_tokens=0
        for doc in local:
            for snapshot in [doc['baseline']]+doc['versions']:
                selected=snapshot['inputs'] if snapshot is doc['baseline'] else [v for v in snapshot['inputs'] if v['changedFromBaseline']]
                full_count+=len(snapshot['inputs']);hash_count+=len(selected)
                full_tokens+=sum(v['tokenCount'] for v in snapshot['inputs']);hash_tokens+=sum(v['tokenCount'] for v in selected)
        batches.append({'batchId':'HASH100-BATCH-%02d'%(index//5+1),'documentIds':[d['documentId'] for d in local],
            'plannedSourceCases':60,'plannedFullInputs':full_count,'plannedHashInputs':hash_count,
            'plannedFullNativeTokens':full_tokens,'plannedHashNativeTokens':hash_tokens})
    return batches

p=argparse.ArgumentParser()
p.add_argument('--jar',type=Path,required=True);p.add_argument('--fixtures',type=Path,required=True)
p.add_argument('--input-plan',type=Path,required=True);p.add_argument('--tokenizer-manifest',type=Path,required=True)
p.add_argument('--state-dir',type=Path,required=True)
mode=p.add_mutually_exclusive_group(required=True);mode.add_argument('--prepare-only',action='store_true');mode.add_argument('--fixture-vectors',action='store_true');mode.add_argument('--real-embedding',action='store_true')
p.add_argument('--batch-id',default='',help='Explicit individual frozen batch; no reordering by output quality')
p.add_argument('--max-batches',type=int,default=20);p.add_argument('--max-seconds-per-batch',type=int,default=7200)
p.add_argument('--stop-after-source-runs',type=int,default=0)
a=p.parse_args()
if not 1<=a.max_batches<=20 or a.max_seconds_per_batch<1 or a.stop_after_source_runs<0:raise SystemExit('Invalid bounded orchestration')
a.state_dir.mkdir(parents=True,exist_ok=True)
lock=(a.state_dir/'.lock').open('a')
try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:raise SystemExit('This frozen experiment already has an active coordinator')
plan=json.loads(a.input_plan.read_text()); batches=prepare(plan,a.fixtures,a.jar,a.tokenizer_manifest)
config={'schema':'hash100-resumable-v1','jar':str(a.jar.resolve()),'jarSha256':sha(a.jar),
    'fixtures':str(a.fixtures.resolve()),'manifestSha256':sha(a.fixtures/'manifest.json'),'inputPlan':str(a.input_plan.resolve()),'inputPlanSha256':sha(a.input_plan),
    'tokenizerManifest':str(a.tokenizer_manifest.resolve()),'tokenizerManifestSha256':sha(a.tokenizer_manifest),
    'helperSourceSha256':sha(R/'scripts/RawHashResumableProbe.java'),'nativeRunnerSourceSha256':sha(R/'scripts/test-hash100-resumable-native.py'),
    'coordinatorSourceSha256':sha(Path(__file__)), 'atomicValidatorSourceSha256':sha(R/'scripts/hash_encoding_identity.py'),
    'batches':batches,'rates':[0,1,5,20,100],'stageDeadlineSeconds':600,'businessAttemptBudget':5,
    'sourceCaseCount':1200,'preparedInputsAreNotExecuted':True}
path=a.state_dir/'plan.json'
if path.exists():
    if json.loads(path.read_text())!=config:raise SystemExit('Original plan/config changed; new experiment required, no recertification')
else:write(path,config)
state_path=a.state_dir/'latest.json'
state=json.loads(state_path.read_text()) if state_path.exists() else {'status':'PREPARED_NOT_EXECUTED','batchAttempts':{},'preparedAt':datetime.now(timezone.utc).isoformat()}
write(state_path,state)
if a.prepare_only:
    print(json.dumps({'status':state['status'],'frozenBatches':len(batches),'plannedSourceCases':1200,'newModelHttpRequests':0,'stateDir':str(a.state_dir)},ensure_ascii=False));raise SystemExit(0)
execution_mode='REAL_WHOLE_RAW_PORT' if a.real_embedding else 'FIXTURE_RUNNER_ONLY'
if state.get('mode',execution_mode)!=execution_mode:raise SystemExit('Cannot mix fixture and actual inference in one experiment')
state['mode']=execution_mode
chosen=[b for b in batches if not a.batch_id or b['batchId']==a.batch_id]
if not chosen:raise SystemExit('Unknown frozen batch')
launched=0
for batch in chosen:
    attempts=state['batchAttempts'].setdefault(batch['batchId'],[])
    previous=Path(attempts[-1]['report']) if attempts else None
    previous_body=json.loads(previous.read_text()) if previous and previous.exists() else None
    if previous_body:
        if attempts[-1].get('reportSha256') and sha(previous)!=attempts[-1]['reportSha256']:
            raise SystemExit('Retained report bytes changed; refusing reuse')
        for report_key,config_key in [('jarSha256','jarSha256'),('manifestSha256','manifestSha256'),
                ('inputPlanSha256','inputPlanSha256'),('helperSourceSha256','helperSourceSha256'),
                ('runnerSourceSha256','nativeRunnerSourceSha256'),('atomicValidatorSourceSha256','atomicValidatorSourceSha256')]:
            if previous_body.get(report_key)!=config[config_key]:
                raise SystemExit('Retained report identity changed: '+report_key)
    if previous_body and previous_body.get('status','').startswith('PASS') and previous_body.get('executedSourceRuns')==60:
        continue
    if launched>=a.max_batches or (a.state_dir/'stop').exists():break
    stem=batch['batchId'].lower()+'-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'-'+uuid.uuid4().hex[:6]
    output=a.state_dir/(stem+'.json')
    command=[sys.executable,str(R/'scripts/test-hash100-resumable-native.py'),'--jar',str(a.jar),'--fixtures',str(a.fixtures),
        '--input-plan',str(a.input_plan),'--tokenizer-manifest',str(a.tokenizer_manifest),'--output',str(output),
        '--real-embedding' if a.real_embedding else '--fixture-vectors','--max-documents','5','--document-ids',','.join(batch['documentIds']),
        '--max-seconds',str(a.max_seconds_per_batch),'--stop-after-source-runs',str(a.stop_after_source_runs),
        '--yield-stop-file',str(a.state_dir/'stop')]
    if previous_body and previous_body.get('nativeCheckpointPath') and Path(previous_body['nativeCheckpointPath']).exists():
        command += ['--resume-from',str(previous)]
    certified=next((r for group in state['batchAttempts'].values() for r in group if r.get('actualAtomicProfile')),None)
    if certified and a.real_embedding:command += ['--expected-profile-from',certified['report']]
    entry={'report':str(output.resolve()),'startedAt':datetime.now(timezone.utc).isoformat(),'command':command}
    attempts.append(entry);write(state_path,state)
    with (a.state_dir/'attempts.jsonl').open('a') as journal:journal.write(json.dumps({'event':'START',**entry})+'\n')
    with output.with_suffix('.console.log').open('w') as log:ran=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    body=json.loads(output.read_text()) if output.exists() else {'status':'FAIL_NO_REPORT'}
    entry.update(exitCode=ran.returncode,status=body['status'],finishedAt=datetime.now(timezone.utc).isoformat(),
        actualAtomicProfile=body.get('actualAtomicServingProfile'),reportSha256=sha(output) if output.exists() else None,
        completedSourceCases=sum(r.get('status')=='PASS' for r in body.get('runs',[])))
    with (a.state_dir/'attempts.jsonl').open('a') as journal:journal.write(json.dumps({'event':'END',**entry})+'\n')
    write(state_path,state);launched+=1
    if ran.returncode!=0:break
reports=[];cost=[]
for batch in batches:
    attempts=state['batchAttempts'].get(batch['batchId'],[])
    for attempt in attempts:
        path=Path(attempt['report']);trace=path.with_suffix('.http.jsonl');preflight=path.with_suffix('.preflight.json')
        if trace.exists():cost.extend(json.loads(line) for line in trace.read_text().splitlines() if line)
        if preflight.exists():cost.append({'profilePreflight':json.loads(preflight.read_text()),'batchId':batch['batchId'],'report':str(path)})
    if attempts and Path(attempts[-1]['report']).exists():reports.append(json.loads(Path(attempts[-1]['report']).read_text()))
complete=len(reports)==20 and all(r.get('status','').startswith('PASS') and r.get('executedSourceRuns')==60 for r in reports)
state.update(status=('PASS_REAL_HASH100_BATCHED_NATIVE_COMPARISON' if a.real_embedding else 'PASS_FIXTURE_BATCH_ORCHESTRATION_NO_EFFICIENCY_CLAIM') if complete else 'INCOMPLETE_RETAINED_HASH100_BATCHES',
    latestUniqueSourceCases=sum(len(r.get('runs',[])) for r in reports),passedUniqueSourceCases=sum(sum(v.get('status')=='PASS' for v in r.get('runs',[])) for r in reports),
    actualHttpCosts=cost,actualNativeAttemptExecutions=sum(len(r.get('attemptRecords',[])) for r in reports),
    finishedAt=datetime.now(timezone.utc).isoformat(),officialHash100Complete=complete and a.real_embedding,efficiencyClaim=False)
write(state_path,state)
print(json.dumps({k:state.get(k) for k in ('status','latestUniqueSourceCases','passedUniqueSourceCases','actualNativeAttemptExecutions','officialHash100Complete','efficiencyClaim')},ensure_ascii=False))
raise SystemExit(0 if complete else 2)
