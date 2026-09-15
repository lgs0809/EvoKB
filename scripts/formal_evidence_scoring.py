"""Score literal evidence with its hash-bound original heading, never filenames or questions.

The public API returns the paragraph body separately from heading_path. Its content_hash
binds both, using KnowledgeDomain.contextualEvidenceContent. Keeping those fields separate
in the UI must not make an original document heading disappear from evaluation evidence.
"""
import hashlib
import re

POLICY = 'raw-body-and-hash-bound-original-heading-v2'


def contextual_evidence(evidence):
    """Return verified original heading + body, or None for malformed/stale evidence."""
    body = evidence.get('raw_content')
    location = evidence.get('raw_location')
    if not isinstance(body, str) or not isinstance(location, dict):
        return None
    headings = location.get('heading_path', [])
    if not isinstance(headings, list) or any(h is not None and not isinstance(h, str) for h in headings):
        return None
    # Match Java String.trim/regex \\s (ASCII), preserving non-ASCII body bytes for SHA.
    body = body.strip(''.join(chr(c) for c in range(33)))
    normalize = lambda s: re.sub(r'[ \t\n\x0b\f\r]+', ' ', s)
    parts = []
    for heading in headings:
        if heading is None or not heading.strip():
            continue
        heading = normalize(heading.strip(''.join(chr(c) for c in range(33))))
        if heading != normalize(body) and heading not in parts:
            parts.append(heading)
    if body.strip() and body not in parts:
        parts.append(body)
    content = '\n'.join(parts)
    digest = hashlib.sha256(content.encode('utf-8')).hexdigest()
    return content if digest == evidence.get('content_hash') else None
