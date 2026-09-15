"""Resolve one current source while retaining failed import history in frozen snapshots."""


def select_active_source(rows, expected):
    active = [r for r in rows if r.get('lifecycle') == 'ACTIVE' and r.get('document_status') == 'ACTIVE']
    # Do not use hash/ACL filtering to conceal two competing ACTIVE source identities.
    if len(active) != 1:
        raise ValueError('Exactly one ACTIVE document/version is required: ' + expected['filename'])
    row = active[0]
    if (row.get('original_filename') != expected['filename'] or row.get('sha256') != expected['sha256']
            or row.get('security_level') != expected['securityLevel']):
        raise ValueError('Current source hash, filename or authority differs: ' + expected['filename'])
    return row
