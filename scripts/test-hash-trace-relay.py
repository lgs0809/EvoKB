"""Actual isolated HTTP byte/status tracing contract; never calls Qwen or benchmark inference."""
import argparse
import ast
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
from types import SimpleNamespace
import urllib.request
import urllib.error
from hash_encoding_identity import canonical_sha256, validate_atomic_response, vector_sha256

p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh report required')
a.output.parent.mkdir(parents=True,exist_ok=True)
source=Path(__file__).resolve().parent/'test-hash-efficiency-native.py'
code=source.read_text();tree=ast.parse(code)
handler_ast=next(node for node in tree.body if isinstance(node,ast.ClassDef) and node.name=='TraceHandler')
trace=a.output.with_suffix('.http.jsonl');context=a.output.with_suffix('.context.json');context.write_text(json.dumps({'scheme':'TRANSPORT_FIXTURE_NOT_MODEL','changeRate':'none'}))
upstream_requests=[]
expected={200:b'{"object":"transport-fixture","data":[{"index":0,"embedding":[1.0]}]}',503:b'{"error":{"message":"owned fixture unavailable"}}'}
class Upstream(BaseHTTPRequestHandler):
 def log_message(self,*_):pass
 def do_POST(self):
  body=self.rfile.read(int(self.headers['Content-Length']));request=json.loads(body)
  upstream_requests.append({'bodySha':hashlib.sha256(body).hexdigest(),'auth':self.headers.get('Authorization'),'purpose':self.headers.get('X-EvoKB-Model-Purpose')})
  status=503 if request['input']==['owned transport failure'] else 200
  payload=expected.get(request['input'][0],expected[status]);self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
backend=ThreadingHTTPServer(('127.0.0.1',0),Upstream);threading.Thread(target=backend.serve_forever,daemon=True).start()
namespace={'BaseHTTPRequestHandler':BaseHTTPRequestHandler,'active_requests':0,'json':json,'context_path':context,'datetime':datetime,'timezone':timezone,'hashlib':hashlib,'trace_lock':threading.Lock(),'records':[],'time':time,'urllib':__import__('urllib'),'upstream':'http://127.0.0.1:'+str(backend.server_address[1]),'a':SimpleNamespace(embedding_timeout=1,real_embedding=False,output=a.output),'trace_path':trace,'validate_atomic_response':validate_atomic_response}
exec(compile(ast.Module(body=[handler_ast],type_ignores=[]),str(source),'exec'),namespace)
relay=ThreadingHTTPServer(('127.0.0.1',0),namespace['TraceHandler']);threading.Thread(target=relay.serve_forever,daemon=True).start()
checks={};observations=[]
try:
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 for status,inputs in [(200,['完整正文；条件不得省略。','second complete input']),(503,['owned transport failure'])]:
  raw=json.dumps({'model':'explicit-transport-fixture','dimensions':1,'input':inputs},ensure_ascii=False).encode()
  request=urllib.request.Request('http://127.0.0.1:'+str(relay.server_address[1])+'/v1/embeddings',data=raw,headers={'Content-Type':'application/json','Authorization':'Bearer fixture-only','X-EvoKB-Model-Purpose':'fixture_transport'},method='POST')
  try:
   response=opener.open(request,timeout=10);actual=response.status;payload=response.read()
  except urllib.error.HTTPError as error:actual=error.code;payload=error.read()
  checks['actualHttp'+str(status)+'StatusAndBodyBytesPreserved']=actual==status and payload==expected[status]
  checks['actualHttp'+str(status)+'RequestBytesAuthAndPurposeUnchanged']=upstream_requests[-1]=={'bodySha':hashlib.sha256(raw).hexdigest(),'auth':'Bearer fixture-only','purpose':'fixture_transport'}
  observations.append({'expectedStatus':status,'actualStatus':actual,'requestSha256':hashlib.sha256(raw).hexdigest(),'responseSha256':hashlib.sha256(payload).hexdigest()})
 deadline=time.monotonic()+3
 while namespace['active_requests'] and time.monotonic()<deadline:time.sleep(.01)
 records=namespace['records'];checks['actualTwoExclusiveRequestRecordsAndInputCounts']=len(records)==2 and [r['attemptedInputCount'] for r in records]==[2,1]
 checks['actualContextAndFailureCapturedWithoutSecretHeaders']=all(r['context']['scheme']=='TRANSPORT_FIXTURE_NOT_MODEL' for r in records) and 'explicit-non-secret-test-value' not in json.dumps(records) and records[1]['httpStatus']==503 and 'owned fixture unavailable' in records[1]['actualErrorResponse']
 checks['zeroInFlightAndPreservedExactResponseHashes']=namespace['active_requests']==0 and [r['responseBodySha256'] for r in records]==[hashlib.sha256(expected[s]).hexdigest() for s in [200,503]]
 profile={'model':'explicit-atomic-fixture','loaded_revision':'a'*40,'tokenizer_sha256':'b'*64,'preprocessor_sha256':'c'*64,'pipeline_sha256':'d'*64,'dimensions':1024,'input_type':'document','normalize':True,'truncation':'reject-over-limit','packages':{'owned-fixture':'1'},'device':'explicit-fixture','parameter_dtype':'explicit-fixture','cpu_attention':'explicit-fixture','resize':'explicit-fixture','cpu_threads':1,'interop_threads':1,'max_tokens':900}
 atomic={'schema':'qwen-encoding-v1','profile':profile,'profile_sha256':canonical_sha256(profile)}
 namespace['a'].real_embedding=True;namespace['tokenizer']={'model':profile['model'],'modelRevision':profile['loaded_revision']};namespace['atomic_expected']=atomic
 vector=[1.0]+[0.0]*1023
 for label,client_status in [('owned certified response',200),('owned uncertified response',502),('owned changed-profile response',502)]:
  certificate={**atomic,'inputs':[{'index':0,'input_sha256':hashlib.sha256(label.encode()).hexdigest(),'vector_sha256':vector_sha256(vector)}]}
  result={'model':profile['model'],'data':[{'index':0,'embedding':vector}],'encoding_identity':certificate}
  if label=='owned uncertified response':del result['encoding_identity']
  if label=='owned changed-profile response':
   changed={**profile,'cpu_threads':2};result['encoding_identity']={**certificate,'profile':changed,'profile_sha256':canonical_sha256(changed)}
  expected[label]=json.dumps(result).encode()
  raw=json.dumps({'model':profile['model'],'dimensions':1024,'input':[label]}).encode()
  request=urllib.request.Request('http://127.0.0.1:'+str(relay.server_address[1])+'/v1/embeddings',data=raw,headers={'Content-Type':'application/json'},method='POST')
  try:
   response=opener.open(request,timeout=10);actual=response.status;payload=response.read()
  except urllib.error.HTTPError as error:actual=error.code;payload=error.read()
  deadline=time.monotonic()+3
  while namespace['active_requests'] and time.monotonic()<deadline:time.sleep(.01)
  record=namespace['records'][-1]
  checks[label+'StatusAndActualCostKept']=actual==client_status and record['httpStatus']==200 and record['clientHttpStatus']==client_status
  checks[label+'CertificationOutcomeCorrect']=record['atomicCertificationAccepted']==(client_status==200)
  checks[label+'OriginalResponseHashAndBytesPreserved']=record['responseBodySha256']==hashlib.sha256(expected[label]).hexdigest() and (payload==expected[label] if client_status==200 else Path(record['actualUncertifiedResponsePath']).read_bytes()==expected[label])
 checks['actualFiveOwnedHttpRecordsNoSharedInference']=len(namespace['records'])==5 and namespace['active_requests']==0
 report={'status':'PASS_ACTUAL_ISOLATED_HTTP_TRACE_CONTRACT' if all(checks.values()) else 'FAIL_ACTUAL_ISOLATED_HTTP_TRACE_CONTRACT','checks':checks,'observations':observations,'records':records,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'source':'test-hash-efficiency-native.py:TraceHandler','actualSharedModelRequests':0,'modelInference':False,'boundary':'Real loopback HTTP relay and owned transport fixture responses only. Extracts frozen TraceHandler syntax from runner without starting its schemas or model path. Not real serving/embedding/benefit validation.'}
finally:relay.shutdown();relay.server_close();backend.shutdown();backend.server_close()
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({'status':report['status'],'checks':checks,'actualSharedModelRequests':0}));raise SystemExit(0 if report['status'].startswith('PASS') else 1)
