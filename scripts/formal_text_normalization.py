"""Conservative lexical facts: whitespace/Markdown formatting and dates, never fuzzy content."""
import re

POLICY='formal-fact-text-v1-whitespace-before-date-numeric-boundaries'

def canonical(text):
    text=re.sub(r'[\s,*_`]', '', text)
    text=re.sub(r'(\d{4})年(\d{1,2})月(\d{1,2})日',
                lambda m:'%s-%02d-%02d' % (m[1],int(m[2]),int(m[3])),text)
    return re.sub(r'(\d{4})-(\d{1,2})-(\d{1,2})',
                  lambda m:'%s-%02d-%02d' % (m[1],int(m[2]),int(m[3])),text)

def contains_fact(text,fact):
    text,fact=canonical(text),canonical(fact)
    if not fact:return False
    # Never let a required 23 match 123, or a contract 001 match 0012.
    before=r'(?<!\d)' if fact[0].isdigit() else ''
    after=r'(?!\d)' if fact[-1].isdigit() else ''
    return re.search(before+re.escape(fact)+after,text) is not None
