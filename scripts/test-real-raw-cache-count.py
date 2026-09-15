"""Actual Qwen with per-component transparent loopback proxy; preserve frozen JAR and all exact attempts."""
from native_probe_runtime import java17_bin
import argparse,hashlib,io,json,os,subprocess,threading,urllib.request,urllib.error,uuid,zipfile
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
R=Path(__file__).resolve().parents[1];p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--jar',type=Path,required=True);p.add_argument('--upstream',default='http://127.0.0.1:8110');a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence required')
work=a.output.with_suffix('.frozen');work.mkdir();records=[];recordLock=threading.Lock()
class Proxy(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  body=self.rfile.read(int(self.headers['Content-Length']));started=datetime.now(timezone.utc).isoformat();status=599;reply=b''
  try:
   with urllib.request.urlopen(urllib.request.Request(a.upstream+self.path,data=body,headers={'Content-Type':'application/json',**({'Authorization':self.headers['Authorization']} if 'Authorization' in self.headers else {})}),timeout=120) as response:status=response.status;reply=response.read()
  except urllib.error.HTTPError as error:status=error.code;reply=error.read()
  finally:
   try:decoded=json.loads(body);inputs=decoded.get('input',[]);inputs=[inputs] if isinstance(inputs,str) else inputs
   except Exception:inputs=[]
   with recordLock:records.append(dict(startedAt=started,finishedAt=datetime.now(timezone.utc).isoformat(),upstream=a.upstream+self.path,status=status,requestBodySha256=hashlib.sha256(body).hexdigest(),fullInputHashes=[hashlib.sha256(x.encode()).hexdigest() for x in inputs],responseBodySha256=hashlib.sha256(reply).hexdigest()))
   self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(reply)));self.end_headers();self.wfile.write(reply)
server=ThreadingHTTPServer(('127.0.0.1',0),Proxy);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();url=f'http://127.0.0.1:{server.server_address[1]}';namespace='acceptance_real_count_'+uuid.uuid4().hex[:12];sql='CREATE SCHEMA '+namespace+';SET search_path TO '+namespace+';\n'+(R/'java/ekb-bootstrap/src/main/resources/db/migration/V13__raw_vector_cache.sql').read_text();a.output.with_suffix('.seed.sql').write_text(sql);r=subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-v','ON_ERROR_STOP=1','--single-transaction','-f','-'],input=sql,capture_output=True,text=True);a.output.with_suffix('.seed.log').write_text(r.stdout+r.stderr);r.check_returncode();jar=a.jar.read_bytes();libs=[]
with zipfile.ZipFile(io.BytesIO(jar)) as z:
 for name in z.namelist():
  if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
   target=work/'lib'/Path(name).name;target.parent.mkdir(exist_ok=True);target.write_bytes(z.read(name));libs.append(target)
source=(R/'scripts/RealRawCacheCountProbe.java').read_bytes();frozen=work/'RealRawCacheCountProbe.java';frozen.write_bytes(source);classes=work/'classes';classes.mkdir();cp=os.pathsep.join(str(x) for x in libs);java=java17_bin();r=subprocess.run([str(java/'javac'),'-J-Xmx128m','-J-XX:ActiveProcessorCount=2','-cp',cp,'-d',str(classes),str(frozen)],capture_output=True,text=True);a.output.with_suffix('.compile.log').write_text(r.stdout+r.stderr);r.check_returncode();settings=dict(line.split('=',1) for line in (R/'.env.target').read_text().splitlines() if line and not line.startswith('#'));env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL='jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema='+namespace+'&stringtype=unspecified');result=None
try:
 result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2','-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]','-cp',str(classes)+os.pathsep+cp,'RealRawCacheCountProbe',str(a.output),str(R/'.tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json'),url],env=env,capture_output=True,text=True,timeout=290);a.output.with_suffix('.runtime.log').write_text(result.stdout+result.stderr)
finally:server.shutdown();server.server_close();thread.join(2);a.output.with_suffix('.actual-http.json').write_text(json.dumps(dict(actualDownstreamRequests=records,requestCount=len(records)),ensure_ascii=False,indent=2))
if not a.output.exists():a.output.write_text(json.dumps(dict(status='FAIL_NATIVE_REAL_QWEN',actualHttpAttempts=records,postgresSchema=namespace),indent=2));raise SystemExit('Real model runtime failed; evidence retained')
report=json.loads(a.output.read_text());report.update(postgresSchema=namespace,dependencyJarSha256=hashlib.sha256(jar).hexdigest(),artifact=str(a.jar),probeSourceSha256=hashlib.sha256(source).hexdigest(),actualHttpAttempts=records,actualHttpRequestCount=len(records),actualProxyUrl=url);report['checks']['exactlyTwoActualSuccessfulWholeInputHttpRequests']=len(records)==2 and all(x['status']==200 for x in records);report['status']='PASS_ACTUAL_QWEN_RAW_CACHE_COMPONENT' if all(report['checks'].values()) else 'FAIL_ACTUAL_QWEN_RAW_CACHE_COMPONENT';a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(dict(status=report['status'],checks=report['checks'],actualHttpRequestCount=len(records)),ensure_ascii=False));raise SystemExit(0 if report['status'].startswith('PASS') and result.returncode==0 else 1)
