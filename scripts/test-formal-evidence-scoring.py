#!/usr/bin/env python3
"""Generic regression checks for the evaluator; no model calls or source/Gold writes."""
import hashlib
import unittest

from formal_evidence_scoring import contextual_evidence


class ContextualEvidenceTest(unittest.TestCase):
    def evidence(self, headings, body, rendered):
        return dict(raw_content=body, raw_location=dict(heading_path=headings),
                    content_hash=hashlib.sha256(rendered.encode()).hexdigest())

    def test_original_heading_and_body_are_available(self):
        value = self.evidence(['Project-17', 'Acceptance'], 'Passed on August 2.',
                              'Project-17\nAcceptance\nPassed on August 2.')
        self.assertIn('Project-17', contextual_evidence(value))

    def test_mutated_or_fabricated_heading_is_rejected(self):
        value = self.evidence(['Project-17'], 'Passed.', 'Project-17\nPassed.')
        value['raw_location']['heading_path'] = ['Project-99']
        self.assertIsNone(contextual_evidence(value))

    def test_mutated_body_is_rejected(self):
        value = self.evidence(['Project-17'], 'Failed.', 'Project-17\nPassed.')
        self.assertIsNone(contextual_evidence(value))

    def test_filename_question_and_unrelated_metadata_are_not_evidence(self):
        value = self.evidence([], 'Passed.', 'Passed.')
        value.update(source_filename='Secret-42.md', question='Secret-42', title='Secret-42')
        self.assertNotIn('Secret-42', contextual_evidence(value))

    def test_body_only_evidence_keeps_original_bytes(self):
        self.assertEqual('Amount  23.', contextual_evidence(self.evidence([], 'Amount  23.', 'Amount  23.')))

    def test_duplicate_headings_and_body_equivalent_heading_follow_java(self):
        self.assertEqual('A\nBody  text', contextual_evidence(
            self.evidence([' A ', 'A', 'Body text'], 'Body  text', 'A\nBody  text')))

    def test_missing_hash_or_malformed_heading_fails_closed(self):
        value = self.evidence(['A'], 'B', 'A\nB')
        value.pop('content_hash')
        self.assertIsNone(contextual_evidence(value))
        value = self.evidence([], 'B', 'B')
        value['raw_location']['heading_path'] = 'not-a-list'
        self.assertIsNone(contextual_evidence(value))


if __name__ == '__main__':
    unittest.main()
