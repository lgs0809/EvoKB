#!/usr/bin/env python3
import copy
import unittest
from formal_answer_delivery import terminal_delivery_valid


class AnswerDeliveryTest(unittest.TestCase):
    def test_refusal_keywords_do_not_certify_an_evidence_only_citation_error(self):
        result = dict(status='ANSWERED', evidence_only=True,
                      answer='回答模型没有返回有效的 Evidence Citation，本次不交付未引用答案。',
                      citations=[{'index': 1, 'evidence': {'evidence_id': 'actually-retrieved'}}])
        before = copy.deepcopy(result)
        self.assertFalse(terminal_delivery_valid(result))
        self.assertEqual(before, result)

    def test_both_factual_answers_and_scoped_refusals_require_actual_delivery(self):
        for text in ('合同约定的金额见原文。[1]', '本次可见资料仅对应另一对象，尚未找到目标原文，无法确认所问金额。[1]'):
            self.assertTrue(terminal_delivery_valid(dict(status='ANSWERED', evidence_only=False,
                answer=text, citations=[{'index': 1, 'evidence': {'evidence_id': 'source'}}])))

    def test_missing_protocol_fields_empty_answers_and_uncited_answers_fail_closed(self):
        base = dict(status='ANSWERED', evidence_only=False, answer='已引用的答复。[1]', citations=[{'index': 1}])
        for key, value in [('evidence_only', None), ('evidence_only', 'false'),
                           ('answer', ''), ('answer', None), ('citations', []), ('citations', None)]:
            with self.subTest(key=key, value=value):
                self.assertFalse(terminal_delivery_valid(dict(base, **{key: value})))

    def test_bounded_insufficiency_is_not_misrepresented_as_a_model_generated_answer(self):
        self.assertTrue(terminal_delivery_valid(dict(status='INSUFFICIENT', evidence_only=True,
            answer='当前可见资料尚不足以确认所问事实。', citations=[])))


if __name__ == '__main__':
    unittest.main()
