"""A typed delivery invariant; refusal keywords never certify a usable model answer."""
POLICY = 'answered-requires-grounded-delivery-v1'


def terminal_delivery_valid(result):
    if result.get('status') != 'ANSWERED':
        return True  # Expected terminal status and insufficient-evidence rules are checked separately.
    return (result.get('evidence_only') is False
            and isinstance(result.get('answer'), str) and bool(result['answer'].strip())
            and isinstance(result.get('citations'), list) and bool(result['citations']))
