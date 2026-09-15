"""Validate the encoder's atomic response, independently of readiness/configuration.

Used only by the retained native benchmark. This does not certify old cached
vectors or change the application's serving adapter.
"""
import hashlib
import json
import math
import re
import struct


def canonical_sha256(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True,
                     separators=(',', ':'), allow_nan=False).encode()
    return hashlib.sha256(raw).hexdigest()


def vector_sha256(vector):
    return hashlib.sha256(struct.pack('<' + 'f' * len(vector), *vector)).hexdigest()


def validate_atomic_response(response, inputs, model, revision, dimensions=1024,
                             expected=None):
    """Fail closed on missing, altered, or differently generated provenance."""
    identity = response.get('encoding_identity')
    if not isinstance(identity, dict) or identity.get('schema') != 'qwen-encoding-v1':
        raise ValueError('Missing supported atomic encoding identity')
    profile = identity.get('profile')
    if not isinstance(profile, dict) or canonical_sha256(profile) != identity.get('profile_sha256'):
        raise ValueError('Atomic profile content/hash mismatch')
    if response.get('model') != model or profile.get('model') != model or profile.get('loaded_revision') != revision:
        raise ValueError('Actual loaded model/revision differs from frozen manifest')
    if (profile.get('input_type') != 'document' or profile.get('dimensions') != dimensions
            or profile.get('normalize') is not True or profile.get('truncation') != 'reject-over-limit'):
        raise ValueError('Atomic whole-document/dimensions/normalization contract mismatch')
    for name in ('tokenizer_sha256', 'preprocessor_sha256', 'pipeline_sha256'):
        if not isinstance(profile.get(name), str) or not re.fullmatch(r'[0-9a-f]{64}', profile[name]):
            raise ValueError('Unknown actual encoder identity: ' + name)
    if (not isinstance(profile.get('packages'), dict) or not profile['packages']
            or any(not isinstance(v, str) or not v for v in profile['packages'].values())
            or not isinstance(profile.get('max_tokens'), int) or profile['max_tokens'] < 1
            or not isinstance(profile.get('cpu_threads'), int) or profile['cpu_threads'] < 1
            or not isinstance(profile.get('interop_threads'), int) or profile['interop_threads'] < 1
            or any(not isinstance(profile.get(k), str) or not profile[k]
                   for k in ('device', 'parameter_dtype', 'cpu_attention', 'resize'))):
        raise ValueError('Incomplete actual package/runtime/precision identity')
    current = {key: identity[key] for key in ('schema', 'profile_sha256', 'profile')}
    if expected is not None and current != expected:
        raise ValueError('Encoder profile changed after benchmark admission')
    rows, proofs = response.get('data'), identity.get('inputs')
    if not isinstance(rows, list) or not isinstance(proofs, list) or len(rows) != len(inputs) or len(proofs) != len(inputs):
        raise ValueError('Atomic input/proof/vector count mismatch')
    if [v.get('index') for v in rows] != list(range(len(inputs))) or [v.get('index') for v in proofs] != list(range(len(inputs))):
        raise ValueError('Atomic response index/order mismatch')
    for text, row, proof in zip(inputs, rows, proofs):
        if not isinstance(text, str) or proof.get('input_sha256') != hashlib.sha256(text.encode()).hexdigest():
            raise ValueError('Atomic input hash differs from complete request text')
        vector = row.get('embedding')
        if (not isinstance(vector, list) or len(vector) != dimensions
                or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in vector)):
            raise ValueError('Atomic vector dimensions/finite values invalid')
        if abs(math.sqrt(sum(v * v for v in vector)) - 1) > 0.0001:
            raise ValueError('Actual normalized vector contract failed')
        if proof.get('vector_sha256') != vector_sha256(vector):
            raise ValueError('Atomic vector hash differs from actual float32 response')
    return current
