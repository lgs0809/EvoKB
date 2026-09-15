#!/usr/bin/env python3
import argparse
import copy
import importlib.util
from pathlib import Path
import unittest
from formal_campaign import resume_attempts, requires_execution
from formal_target import FormalTarget

_compare_spec = importlib.util.spec_from_file_location(
    'formal_compare', Path(__file__).with_name('compare-formal-query-evaluation.py'))
_compare_module = importlib.util.module_from_spec(_compare_spec)
_compare_spec.loader.exec_module(_compare_module)


class FormalTargetTest(unittest.TestCase):
    def test_replay_uses_distinct_storage_and_keeps_legacy_selection(self):
        parser = argparse.ArgumentParser()
        FormalTarget.add_selection(parser)
        managed = FormalTarget.selected(parser.parse_args([]))
        original = FormalTarget.selected(parser.parse_args(['--isolated']))
        named = FormalTarget.selected(parser.parse_args(['--target', 'holdout']))
        replay = FormalTarget.selected(parser.parse_args(['--target', 'holdout-replay']))
        self.assertEqual(vars(original), vars(named))
        for field in ('database', 'base', 'private', 'bucket', 'redis_database', 'stream', 'session_cookie'):
            self.assertEqual(3, len({getattr(target, field) for target in (managed, original, replay)}), field)


class FormalCampaignTest(unittest.TestCase):
    def setUp(self):
        self.frozen = dict(manifestSha256='gold', condition='baseline', process={'jar': 'same'},
                           sourceSnapshot={'hashes': ['original']}, runtimeSnapshot={'model': 'approved'},
                           declaredCases=['a', 'b'], plannedAttempts=4)
        self.frozen.update(forbiddenClaimPolicy='policy-v2', answerDeliveryPolicy='delivery-v1',
                           scoringHelpers={'facts': 'hash'})

    def prior(self, rows):
        return dict(self.frozen, attempts=rows)

    def test_finished_success_and_failure_are_retained_while_known_session_is_resumed(self):
        rows = [dict(caseId='a', repeat=1, outcome='FAIL_STRICT_FACTS_OR_EVIDENCE'),
                dict(caseId='b', repeat=1, outcome='PASS_STRICT_FACTS_AND_EVIDENCE'),
                dict(caseId='a', repeat=2, startHttpStatus=200, sessionId='existing')]
        prior = self.prior(rows)
        result = resume_attempts(prior, self.frozen)
        self.assertFalse(requires_execution(result[('a', 1)]))
        self.assertFalse(requires_execution(result[('b', 1)]))
        self.assertTrue(requires_execution(result[('a', 2)]))
        self.assertEqual('existing', result[('a', 2)]['sessionId'])
        result[('a', 2)]['sessionId'] = 'changed-copy'
        self.assertEqual('existing', prior['attempts'][2]['sessionId'])
        self.assertTrue(requires_execution(None))

    def test_any_frozen_identity_change_rejects_resume(self):
        for key in self.frozen:
            changed = copy.deepcopy(self.frozen)
            changed[key] = 'different'
            with self.assertRaises(ValueError, msg=key):
                resume_attempts(self.prior([]), changed)

    def test_duplicate_unknown_or_out_of_plan_attempts_fail_closed(self):
        for rows in ([dict(caseId='a', repeat=1)] * 2, [dict(caseId='other', repeat=1)],
                     [dict(caseId='a', repeat=3)], [dict(caseId='a', repeat=0)]):
            with self.assertRaises(ValueError):
                resume_attempts(self.prior(rows), self.frozen)

    def test_unconfirmed_successful_admission_is_not_repeated(self):
        for row in (dict(caseId='a', repeat=1, startHttpStatus=200), dict(caseId='a', repeat=1)):
            with self.assertRaises(ValueError):
                resume_attempts(self.prior([row]), self.frozen)


class FormalComparisonTest(unittest.TestCase):
    def setUp(self):
        self.raw = dict(status='COMPLETED_ACTUAL_FORMAL_QUERIES', condition='baseline',
                        unstartedAttempts=0, manifestSha256='gold', runnerSha256='runner', process={'jar': 'fixed'},
                        runtimeSnapshot={'model': 'fixed'}, declaredCases=['a', 'b'], plannedAttempts=2,
                        scoringHelpers={}, forbiddenClaimPolicy='claims', factTextPolicy='facts',
                        answerDeliveryPolicy='delivery', workerCount=1,
                        finishedAt='2026-11-10T00:10:00+00:00',
                        sourceSnapshot=dict(sources=['fixed'], derived=[], summaries=[], graph=[]),
                        attempts=[dict(caseId='a', repeat=1, outcome='FAIL_START'),
                                  dict(caseId='b', repeat=1, outcome='PASS', actualConsumedRounds=2)])
        self.layered = copy.deepcopy(self.raw)
        self.layered.update(condition='layered', startedAt='2026-11-10T00:11:00+00:00')
        self.layered['sourceSnapshot']['derived'] = ['published']
        self.layered['attempts'][1].update(actualConsumedRounds=1, expandedDerivedRevisions=2)

    def test_keeps_failed_questions_in_the_denominator(self):
        comparison = _compare_module.compare(self.raw, self.layered)
        self.assertEqual(2, comparison['pairCount'])
        self.assertEqual(1, comparison['rawOutcomes']['FAIL_START'])
        self.assertEqual(1, comparison['pairsWithNativeRoundCounts'])
        self.assertEqual(1, comparison['layeredQueriesWithNativeEntryExpansion'])

    def test_rejects_raw_derived_content_runner_drift_and_incomplete_attempt_identity(self):
        for change in ('raw-derived', 'runner', 'identity'):
            raw, layered = copy.deepcopy((self.raw, self.layered))
            if change == 'raw-derived':
                raw['sourceSnapshot']['summaries'] = ['unexpected']
            elif change == 'runner':
                layered['runnerSha256'] = 'other'
            else:
                raw['attempts'][1]['caseId'] = layered['attempts'][1]['caseId'] = 'unknown'
            with self.assertRaises(ValueError, msg=change):
                _compare_module.compare(raw, layered)


if __name__ == '__main__':
    unittest.main()
