"""Conservative lexical assertion detection, distinguishing question headings from answers."""
import re

POLICY = 'clause-scoped-atomic-assertions-v3'


def compact(text):
    return re.sub(r'[\s,*_`#]', '', text)


def asserted(text, phrase):
    phrase = compact(phrase)
    if not phrase:
        return False
    # A forbidden amount such as 23万元 is not the distinct public amount 123万元.
    # Preserve sentence/question/negation handling while matching complete numbers.
    pattern = (r'(?<![\d.])' if phrase[0].isdigit() else '') + re.escape(phrase)
    if phrase[-1].isdigit():
        pattern += r'(?![\d.])'
    for raw in re.split(r'[。；;，,！？?\n]', text):
        clause = compact(raw)
        for match in re.finditer(pattern, clause):
            before = re.split(r'但是|然而|不过|但', clause[:match.start()])[-1]
            if re.search(r'无法|不能|没有|不证明|未确认|不代表|并非|尚未|未|不', before[-40:]):
                continue
            if re.search(r'是否|能否|可否|有没有|是不是|能不能', before):
                continue
            if re.match(r'^[”"\'）)]*[吗么]', clause[match.end():]):
                continue
            return True
    return False
