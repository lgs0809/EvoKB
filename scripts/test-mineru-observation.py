#!/usr/bin/env python3
"""Native stdlib diagnostics/resource cleanup; no parser/model inference or altered deadline."""
import argparse,ast,faulthandler,hashlib,io,json,tempfile,unittest
from datetime import datetime,timezone
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'scripts/mineru-service/app.py'
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists(): raise SystemExit('Retain prior reports; use a fresh output')
source=APP.read_bytes(); tree=ast.parse(source)
function=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='native_stack_sampling')
from contextlib import contextmanager
namespace=dict(contextmanager=contextmanager,faulthandler=faulthandler,json=json,now=lambda:datetime.now(timezone.utc).isoformat(),VERSION='3.4.5',sha256=lambda f:hashlib.sha256(f.read_bytes()).hexdigest(),Path=Path,__file__=str(APP))
exec(compile(ast.Module(body=[function],type_ignores=[]),str(APP),'exec'),namespace)
observe=namespace['native_stack_sampling']
class ObservationTests(unittest.TestCase):
 def workspace(self):
  temp=tempfile.TemporaryDirectory(prefix='evokb-native-observer-');self.addCleanup(temp.cleanup);return Path(temp.name)
 def test_actual_native_failure_keeps_exception_and_stack_without_local_values(self):
  root=self.workspace();stages=[];failure=RuntimeError('synthetic-private-credential-should-never-appear')
  with self.assertRaises(RuntimeError) as raised:
   with observe(root,lambda s,**kw:stages.append(dict(stage=s,**kw))):raise failure
  self.assertIs(raised.exception,failure)
  raw=(root/'native-stacks.log').read_text()
  self.assertIn('test_actual_native_failure',raw)
  self.assertNotIn(str(failure),raw)
  self.assertNotIn(str(failure),json.dumps(stages))
  self.assertEqual(stages[-1]['stage'],'native-stack-sampling-stopped')
  self.assertEqual(stages[-2]['errorType'],'RuntimeError')
 def test_success_returns_native_value_and_stops_timer_before_stream_close(self):
  root=self.workspace();events=[];value=object()
  def arm(*args,**kw):events.append(('armed',args,kw['repeat'],kw['file'].closed));self.stream=kw['file']
  def cancel():events.append(('cancelled',self.stream.closed))
  with patch.object(faulthandler,'dump_traceback_later',side_effect=arm),patch.object(faulthandler,'cancel_dump_traceback_later',side_effect=cancel):
   with observe(root,lambda s,**kw:None):returned=value
  self.assertIs(returned,value);self.assertEqual(events,[('armed',(30,),True,False),('cancelled',False)]);self.assertTrue(self.stream.closed)
 def test_observation_start_failure_still_cancels_owned_timer(self):
  root=self.workspace();failure=OSError('synthetic-observation-start');cancel=[]
  def mark(stage,**kw):
   if stage=='native-stack-sampling-started':raise failure
  with patch.object(faulthandler,'dump_traceback_later'),patch.object(faulthandler,'dump_traceback'),patch.object(faulthandler,'cancel_dump_traceback_later',side_effect=lambda:cancel.append(True)):
   with self.assertRaises(OSError) as raised:
    with observe(root,mark):self.fail('No native code may execute after failed diagnostic setup')
  self.assertIs(raised.exception,failure);self.assertEqual(cancel,[True])
 def test_real_consecutive_tasks_have_separate_trace_files(self):
  first=self.workspace();second=self.workspace()
  for root in (first,second):
   with self.assertRaises(ValueError):
    with observe(root,lambda s,**kw:None):raise ValueError('synthetic task detail')
  self.assertTrue((first/'native-stacks.log').is_file());self.assertTrue((second/'native-stacks.log').is_file())
  self.assertNotIn(str(first),(second/'native-stacks.log').read_text())
 def test_cancellation_identity_is_not_changed(self):
  root=self.workspace();failure=KeyboardInterrupt();cancel=[]
  with patch.object(faulthandler,'dump_traceback_later'),patch.object(faulthandler,'dump_traceback'),patch.object(faulthandler,'cancel_dump_traceback_later',side_effect=lambda:cancel.append(True)):
   with self.assertRaises(KeyboardInterrupt) as raised:
    with observe(root,lambda s,**kw:None):raise failure
  self.assertIs(raised.exception,failure);self.assertEqual(cancel,[True])
 def test_native_call_and_production_budget_are_unchanged(self):
  # A stable declared contract survives squashing/history reconstruction. HEAD may
  # already contain a changed implementation and cannot certify the old boundary.
  baseline=ast.parse('''
VERSION='3.4.5'
STAGE_SECONDS=min(600,max(1,int(os.environ.get('EKB_MINERU_STAGE_SECONDS','600'))))
CPU_THREADS=int(os.environ.get('EKB_MINERU_CPU_THREADS','2'))
do_parse(str(output),['source'],[original.read_bytes()],[job['language']],backend=job['backend'],effort=job['effort'],parse_method=job['parseMethod'],f_draw_layout_bbox=False,f_draw_span_bbox=False,f_dump_model_output=True)
''')
  def call(t):return [ast.dump(n,include_attributes=False) for n in ast.walk(t) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='do_parse']
  def config(t):return [ast.dump(n,include_attributes=False) for n in t.body if isinstance(n,ast.Assign) and any(isinstance(x,ast.Name) and x.id in ('VERSION','STAGE_SECONDS','CPU_THREADS') for x in n.targets)]
  self.assertEqual(call(tree),call(baseline));self.assertEqual(config(tree),config(baseline))
  self.assertNotIn('setprofile',source.decode());self.assertNotIn('settrace',source.decode())
stream=io.StringIO();result=unittest.TextTestRunner(stream=stream,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ObservationTests))
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.with_suffix('.log').write_text(stream.getvalue())
report=dict(status='PASS_STDLIB_NATIVE_OBSERVATION' if result.wasSuccessful() else 'FAIL_STDLIB_NATIVE_OBSERVATION',operationTime=datetime.now(timezone.utc).isoformat(),tests=result.testsRun,failures=len(result.failures),errors=len(result.errors),skipped=len(result.skipped),sourceSha256=hashlib.sha256(source).hexdigest(),probeSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),nativeContract='Declared MinerU3.4.5/source pages/profile kwargs/600s ceiling and configured CPU threads, independent of Git HEAD',scope='Actual faulthandler exception stack and owned-timer lifecycle plus immutable native invocation contract; no actual MinerU inference/model/600s timeout diagnosis')
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False));raise SystemExit(0 if result.wasSuccessful() else 1)
