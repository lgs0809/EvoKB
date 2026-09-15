#!/usr/bin/env python3
"""Current identity is unambiguous even when failed historical imports are retained."""
import unittest

from formal_source_identity import select_active_source


class SourceIdentityTest(unittest.TestCase):
    expected = dict(filename='contract.md', sha256='frozen', securityLevel=10)

    def active(self, **changes):
        return dict(dict(version_id='current', lifecycle='ACTIVE', document_status='ACTIVE',
                         original_filename='contract.md', sha256='frozen', security_level=10), **changes)

    def test_one_current(self):
        self.assertEqual('current', select_active_source([self.active()], self.expected)['version_id'])

    def test_failed_history_is_retained_without_ambiguous_current_identity(self):
        old = self.active(version_id='failed', lifecycle='FAILED', document_status='FAILED')
        rows = [old, self.active()]
        self.assertEqual('current', select_active_source(rows, self.expected)['version_id'])
        self.assertEqual(2, len(rows))
        self.assertEqual('FAILED', rows[0]['lifecycle'])

    def test_two_active_versions_fail_even_when_one_hash_is_correct(self):
        with self.assertRaises(ValueError):
            select_active_source([self.active(), self.active(version_id='other', sha256='other')], self.expected)

    def test_no_active_source_fails(self):
        for rows in [[], [self.active(lifecycle='FAILED')], [self.active(document_status='FAILED')]]:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                select_active_source(rows, self.expected)

    def test_wrong_current_hash_filename_or_acl_fails(self):
        for changes in [dict(sha256='changed'), dict(original_filename='other.md'), dict(security_level=30)]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                select_active_source([self.active(**changes)], self.expected)


if __name__ == '__main__':
    unittest.main()
