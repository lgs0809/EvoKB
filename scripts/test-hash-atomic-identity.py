"""Replay retained actual bytes and reject altered identities; zero new inference."""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
from hash_encoding_identity import canonical_sha256, validate_atomic_response

p = argparse.ArgumentParser()
p.add_argument('--recorded-response', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh evidence required')
saved = json.loads(a.recorded_response.read_text())
response = saved['response']
text = Path(saved['sourcePath']).read_text()
profile = response['encoding_identity']['profile']
expected = {k: response['encoding_identity'][k] for k in ('schema', 'profile', 'profile_sha256')}
checks = {}
try:
    validate_atomic_response(response, [text], profile['model'], profile['loaded_revision'], expected=expected)
    checks['recordedActualFullInputAndVectorAcceptedWithoutNewInference'] = True
except Exception:
    checks['recordedActualFullInputAndVectorAcceptedWithoutNewInference'] = False

def reject(name, changed, inputs=None):
    try:
        validate_atomic_response(changed, inputs or [text], profile['model'], profile['loaded_revision'], expected=expected)
        checks[name] = False
    except (ValueError, TypeError, OverflowError):
        checks[name] = True

changed = deepcopy(response); del changed['encoding_identity']; reject('missingAtomicIdentityRejected', changed)
changed = deepcopy(response); changed['encoding_identity']['profile']['cpu_threads'] += 1; reject('tamperedCanonicalProfileHashRejected', changed)
changed['encoding_identity']['profile_sha256'] = canonical_sha256(changed['encoding_identity']['profile']); reject('validDifferentRuntimeProfileRejected', changed)
changed = deepcopy(response); changed['encoding_identity']['inputs'][0]['input_sha256'] = '0' * 64; reject('truncatedOrDifferentCompleteInputRejected', changed)
changed = deepcopy(response); changed['data'][0]['embedding'][0] += .01; reject('modifiedReturnedVectorRejected', changed)
changed = deepcopy(response); changed['data'][0]['embedding'].pop(); reject('wrongActualDimensionsRejected', changed)
changed = deepcopy(response); changed['data'][0]['embedding'][0] = float('nan'); reject('nonFiniteVectorRejected', changed)
changed = deepcopy(response); changed['data'][0]['index'] = 1; reject('wrongAtomicVectorIndexRejected', changed)
changed = deepcopy(response); changed['encoding_identity']['inputs'].append(changed['encoding_identity']['inputs'][0]); reject('incorrectProofCountRejected', changed)
changed = deepcopy(response); changed['model'] = 'another-model'; reject('differentActualModelRejected', changed)
report = {'status': 'PASS_RECORDED_ATOMIC_IDENTITY_VALIDATION' if all(checks.values()) else 'FAIL_RECORDED_ATOMIC_IDENTITY_VALIDATION',
          'checks': checks, 'recordedResponseSha256': hashlib.sha256(a.recorded_response.read_bytes()).hexdigest(),
          'validatorSha256': hashlib.sha256((Path(__file__).parent / 'hash_encoding_identity.py').read_bytes()).hexdigest(),
          'actualNewModelCalls': 0,
          'boundary': 'Pure replay of previous real certified response plus deliberate local mutations. No new runtime admission, model quality, or benchmark benefit claim.'}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False)); raise SystemExit(0 if all(checks.values()) else 1)
