import unittest
from formal_text_normalization import canonical,contains_fact

class FactTextNormalizationTest(unittest.TestCase):
    def test_rendered_date_spacing_and_markdown_do_not_change_the_date(self):
        for text in ['2026年8月2日','2026 年 8 月 2 日','2026 年\n8 月\t2 日',
                     '**2026 年 8 月 2 日**','2026-8-2','2026 - 08 - 02','`2026-08-02`']:
            with self.subTest(text=text):
                self.assertEqual('2026-08-02',canonical(text))
                self.assertTrue(contains_fact('书面验收：'+text+'。','2026-08-02'))

    def test_wrong_dates_and_other_contract_ids_remain_wrong(self):
        for text in ['2026 年 8 月 3 日','2025 年 8 月 2 日','2026-09-02','12026-08-02','2026-08-020']:
            with self.subTest(text=text):self.assertFalse(contains_fact(text,'2026-08-02'))
        self.assertFalse(contains_fact('HT-EKB-0012','HT-EKB-001'))
        self.assertTrue(contains_fact('合同 HT-EKB-001，采购方甲。','HT-EKB-001'))

    def test_exact_numeric_facts_are_not_substrings_of_a_different_amount_or_deadline(self):
        for text,fact in [('123万元','23万元'),('111日','11日'),('234','23'),('123','23')]:
            with self.subTest(text=text,fact=fact):self.assertFalse(contains_fact(text,fact))
        self.assertTrue(contains_fact('已登记人民币 **23 万元**。','23万元'))
        self.assertTrue(contains_fact('原32日改为11日。','11日'))

    def test_no_fuzzy_equivalence_or_missing_facts_are_invented(self):
        for text,fact in [('未知','23万元'),('二十三万元','23万元'),('HT-EKB-002','HT-EKB-001'),('','23万元'),('任意文本','')]:
            with self.subTest(text=text,fact=fact):self.assertFalse(contains_fact(text,fact))

if __name__=='__main__':unittest.main()
