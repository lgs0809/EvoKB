#!/usr/bin/env python3
"""Pure synthetic task-observation selection tests; no database or model execution."""
import argparse
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'scripts/test-target-stable-entry-publication.py'
spec = importlib.util.spec_from_file_location('actual_entry_readback', SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
derived_spec = importlib.util.spec_from_file_location('actual_derived_readback', ROOT/'scripts/test-target-stable-derived-publication.py')
derived = importlib.util.module_from_spec(derived_spec)
derived_spec.loader.exec_module(derived)


class ExecutionBindingTest(unittest.TestCase):
    def setUp(self):
        self.process = dict(pid=123, jarSha256='fixture-immutable-jar',
                            startedAt='2026-10-07T05:17:37+00:00')
        self.new = dict(task_id='new', version_id='source', task_type='CONCEPT',
                        status='SUCCEEDED', subject_id='summary/a/acl/1/content/hash',
                        started_at='2026-10-07T05:18:00+00:00')
        self.old = dict(self.new, task_id='old', started_at='2026-10-06T05:18:00+00:00')
        self.prior = dict(process=deepcopy(self.process), database=dict(tasks=[self.old, self.new]))

    def selected(self, prior=None, task='new', process=None):
        return module.select_observed_execution(prior or self.prior, 'source', task,
                                                self.process if process is None else process)

    def test_explicit_new_succeeded_task_preserves_and_ignores_old_success(self):
        before = deepcopy(self.prior)
        self.assertEqual('new', self.selected()['task_id'])
        self.assertEqual(before, self.prior)

    def test_failed_new_cannot_be_replaced_by_old_success(self):
        self.new['status'] = 'FAILED'
        with self.assertRaises(ValueError): self.selected()

    def test_running_new_cannot_be_replaced_by_old_success(self):
        self.new['status'] = 'RUNNING'
        with self.assertRaises(ValueError): self.selected()

    def test_missing_explicit_task_rejects(self):
        with self.assertRaises(ValueError): self.selected(task='absent')

    def test_duplicate_explicit_task_rejects(self):
        self.prior['database']['tasks'].append(deepcopy(self.new))
        with self.assertRaises(ValueError): self.selected()

    def test_wrong_source_rejects(self):
        self.new['version_id'] = 'other'
        with self.assertRaises(ValueError): self.selected()

    def test_wrong_task_kind_rejects(self):
        self.new['task_type'] = 'SUMMARY'
        with self.assertRaises(ValueError): self.selected()

    def test_legacy_subject_does_not_certify_current_regeneration(self):
        self.new['subject_id'] = 'old-summary-id'
        with self.assertRaises(ValueError): self.selected()

    def test_other_reader_artifact_rejects_current_task(self):
        with self.assertRaises(ValueError): self.selected(process=dict(self.process, jarSha256='other'))

    def test_restarted_reader_rejects_same_artifact(self):
        with self.assertRaises(ValueError): self.selected(process=dict(self.process, startedAt='2026-10-07T05:19:00+00:00'))

    def test_other_pid_rejects_same_start_and_artifact(self):
        with self.assertRaises(ValueError): self.selected(process=dict(self.process, pid=124))

    def test_execution_before_reader_start_rejects(self):
        self.new['started_at'] = '2026-10-07T05:16:00+00:00'
        with self.assertRaises(ValueError): self.selected()

    def test_execution_equal_reader_start_rejects(self):
        self.new['started_at'] = self.process['startedAt']
        with self.assertRaises(ValueError): self.selected()

    def test_postgres_fractional_trailing_zero_omission_preserves_order(self):
        self.process['startedAt']='2026-11-02T16:13:03.418849+00:00'
        self.prior['process']=deepcopy(self.process)
        self.new['started_at']='2026-11-02T16:13:03.41885+00:00'
        self.assertEqual('new',self.selected()['task_id'])
        self.process['startedAt']='2026-11-02T16:13:03.418850+00:00'
        self.prior['process']=deepcopy(self.process)
        with self.assertRaises(ValueError):self.selected()

    def test_projection_command_needs_explicit_kind_and_actual_success(self):
        self.new['subject_id']='command/exact-frozen-hash'
        with self.assertRaises(ValueError):self.selected()
        self.assertEqual('new',module.select_observed_execution(self.prior,'source','new',self.process,subject_prefix='command/')['task_id'])
        self.new['status']='FAILED'
        with self.assertRaises(ValueError):module.select_observed_execution(self.prior,'source','new',self.process,subject_prefix='command/')

    def test_unstarted_execution_rejects(self):
        self.new['started_at'] = None
        with self.assertRaises(ValueError): self.selected()

    def test_original_default_single_generation_oracle_unchanged(self):
        prior = dict(process=self.process, database=dict(tasks=[self.new]))
        self.assertEqual('new', module.select_observed_execution(prior, 'source')['task_id'])

    def test_original_default_multiple_success_oracle_unchanged(self):
        with self.assertRaises(ValueError): module.select_observed_execution(self.prior, 'source')


class DerivedBindingTest(unittest.TestCase):
    def fixture(self, kind):
        process=dict(pid=123,jarSha256='fixture-immutable',startedAt='2026-10-07T05:17:37+00:00')
        task=dict(task_id='new-'+kind,version_id='source',task_type=kind,status='SUCCEEDED',
                  subject_id='entry/fixture/revision/2',started_at='2026-10-07T05:18:00+00:00')
        old=dict(task,task_id='old-'+kind,started_at='2026-10-06T05:18:00+00:00')
        return process,task,dict(process=process,database=dict(tasks=[old,task]))

    def choose(self, kind, process, task, prior):
        return module.select_observed_execution(prior,'source',task['task_id'],process,kind,'entry/')

    def test_both_actual_stable_kinds_choose_only_the_new_task(self):
        for kind in ['GRAPH','WIKI']:
            with self.subTest(kind=kind):
                process,task,prior=self.fixture(kind);before=deepcopy(prior)
                self.assertEqual(task,self.choose(kind,process,task,prior));self.assertEqual(before,prior)

    def test_failed_stable_task_cannot_substitute_old_success(self):
        for kind in ['GRAPH','WIKI']:
            with self.subTest(kind=kind):
                process,task,prior=self.fixture(kind);task['status']='FAILED'
                with self.assertRaises(ValueError): self.choose(kind,process,task,prior)

    def test_wrong_stable_kind_cannot_substitute(self):
        for kind in ['GRAPH','WIKI']:
            with self.subTest(kind=kind):
                process,task,prior=self.fixture(kind);task['task_type']='CONCEPT'
                with self.assertRaises(ValueError): self.choose(kind,process,task,prior)

    def test_legacy_summary_subject_is_not_stable_entry_revision(self):
        for kind in ['GRAPH','WIKI']:
            with self.subTest(kind=kind):
                process,task,prior=self.fixture(kind);task['subject_id']='summary/legacy'
                with self.assertRaises(ValueError): self.choose(kind,process,task,prior)

    def test_same_artifact_other_process_does_not_certify_stable_execution(self):
        for kind in ['GRAPH','WIKI']:
            with self.subTest(kind=kind):
                process,task,prior=self.fixture(kind)
                with self.assertRaises(ValueError): self.choose(kind,dict(process,pid=456),task,prior)

    def test_graph_native_and_public_views_keep_same_identity_and_proofs(self):
        native=dict(relationId='r',sourceEntityId='s',sourceName='S',sourceType='COMPANY',targetEntityId='t',
                    targetName='T',targetType='COMPANY',relationType='SUPPLIES',validFrom='2026-10-07T00:00:00Z',
                    validTo=None,conflictStatus='NONE',evidenceIds=['b','a'],score=1.0)
        public=dict(relation_id='r',source_entity_id='s',source_name='S',source_type='COMPANY',target_entity_id='t',
                    target_name='T',target_type='COMPANY',relation_type='SUPPLIES',valid_from='2026-10-07T00:00:00+00:00',
                    valid_to=None,conflict_status='NONE',evidence_ids=['a','b'],score=1.0)
        self.assertEqual(derived.graph_rows([native]),derived.graph_rows([public]))
        public['evidence_ids']=['a'];self.assertNotEqual(derived.graph_rows([native]),derived.graph_rows([public]))

    def test_duplicate_public_graph_identity_is_rejected(self):
        row=dict(relation_id='r',evidence_ids=['a'])
        with self.assertRaises(ValueError): derived.graph_rows([row,row])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    log = args.output.with_suffix('.log')
    if args.output.exists() or log.exists(): raise ValueError('Use a fresh output stem')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    captured = io.StringIO()
    suite=unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(cls)
                             for cls in [ExecutionBindingTest,DerivedBindingTest]])
    result = unittest.TextTestRunner(stream=captured, verbosity=2).run(suite)
    log.write_text(captured.getvalue())
    report = dict(status='PASS_SYNTHETIC_READER_BINDING' if result.wasSuccessful() else 'FAIL_SYNTHETIC_READER_BINDING',
                  total=result.testsRun, passed=result.testsRun-len(result.failures)-len(result.errors),
                  failed=len(result.failures), errors=len(result.errors),
                  readerSourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
                  testSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  derivedReaderSourceSha256=hashlib.sha256((ROOT/'scripts/test-target-stable-derived-publication.py').read_bytes()).hexdigest(),
                  boundary='Pure synthetic observation selection. No native database/model/vector/object/UI calls; separate from the deployed Java test count.')
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(report))
    raise SystemExit(0 if result.wasSuccessful() else 1)
