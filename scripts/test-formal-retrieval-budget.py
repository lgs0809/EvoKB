#!/usr/bin/env python3
import unittest

from formal_retrieval_budget import within_query_budget


class QueryBudgetTest(unittest.TestCase):
    def test_nine_actual_queries_fail_even_with_only_three_rounds(self):
        self.assertFalse(within_query_budget([
            dict(round_no=n, input_queries=['first', 'second', 'third']) for n in range(1, 4)]))

    def test_eight_actual_queries_within_per_round_limit_pass(self):
        self.assertTrue(within_query_budget([
            dict(round_no=1, input_queries=['one', 'two', 'three']),
            dict(round_no=2, input_queries=['four', 'five', 'six']),
            dict(round_no=3, input_queries=['seven', 'eight'])]))

    def test_four_queries_in_one_round_fail(self):
        self.assertFalse(within_query_budget([dict(round_no=1, input_queries=['a', 'b', 'c', 'd'])]))

    def test_duplicate_round_or_malformed_query_accounting_fails(self):
        for rows in [[None], [dict(round_no=1, input_queries=['a'])] * 2,
                     [dict(round_no=1)], [dict(round_no=1, input_queries=[''])],
                     [dict(round_no=9, input_queries=['a'])]]:
            with self.subTest(rows=rows):
                self.assertFalse(within_query_budget(rows))

    def test_no_queries_is_within_budget_but_does_not_prove_answer_success(self):
        self.assertTrue(within_query_budget([]))


if __name__ == '__main__':
    unittest.main()
