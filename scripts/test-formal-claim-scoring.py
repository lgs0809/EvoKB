#!/usr/bin/env python3
import unittest
from formal_claim_scoring import asserted


class ClaimScoringTest(unittest.TestCase):
    def test_question_headings_and_negative_answers_are_not_assertions(self):
        for text in ('### 是否已获批准\n不能证明。', '**已获批准吗？**\n没有相关记录。',
                     '现有材料不足，不能确认项目已获批准。', '能否确定项目已获批准？'):
            self.assertFalse(asserted(text, '已获批准'))

    def test_a_question_does_not_hide_a_positive_answer_or_a_later_contradiction(self):
        for text in ('是否已获批准？\n项目已获批准。', '无法找到原件，但项目已获批准。',
                     '不能确认其他事项；项目已获批准。', '**项目已获批准**。'):
            self.assertTrue(asserted(text, '已获批准'))

    def test_negation_in_a_different_sentence_does_not_leak_across_boundaries(self):
        self.assertTrue(asserted('没有风险说明。项目已获批准。', '已获批准'))
        self.assertTrue(asserted('没有风险说明\n项目已获批准', '已获批准'))

    def test_absent_or_differently_scoped_phrase_does_not_create_a_match(self):
        self.assertFalse(asserted('尚无采购凭证。', '已获批准'))

    def test_quantities_match_whole_numbers_and_preserve_real_disclosures(self):
        for text in ('公开合同金额为123万元。', '公开费用为1.23万元。', '总价为223万元。'):
            self.assertFalse(asserted(text, '23万元'))
        self.assertTrue(asserted('已支付23万元。', '23万元'))
        self.assertTrue(asserted('合同总价123万元，但已支付23万元。', '23万元'))
        self.assertFalse(asserted('合同总价123万元。', '23'))
        self.assertFalse(asserted('编号1234。', '123'))
        self.assertFalse(asserted('没有证据证明已支付23万元。', '23万元'))


if __name__ == '__main__':
    unittest.main()
