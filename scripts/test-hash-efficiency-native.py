"""Fair immutable native Raw full/Hash comparison, with explicit real or fixture mode.

Fixture mode never requests the model and cannot produce a measured benefit.
Real mode transparently traces only this process's original HTTP requests on an
ephemeral loopback port; it does not restart/configure the shared serving system.
All source/SQL/MinIO/Milvus objects and every failed/partial result are retained.
"""
from native_probe_runtime import java17_bin
import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from hash_encoding_identity import validate_atomic_response

R = Path(__file__).resolve().parents[1]
JAVA = java17_bin()
p = argparse.ArgumentParser()
p.add_argument('--jar', type=Path, required=True)
p.add_argument('--fixtures', type=Path, required=True)
p.add_argument('--input-plan', type=Path, required=True)
p.add_argument('--tokenizer-manifest', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
mode = p.add_mutually_exclusive_group(required=True)
mode.add_argument('--fixture-vectors', action='store_true', help='Runner correctness only; zero model HTTP calls, no efficiency claim')
mode.add_argument('--real-embedding', action='store_true', help='Requires an explicitly coordinated shared model window; real inference/cost')
p.add_argument('--max-documents', type=int, default=100, help='A subset can validate the runner but never count as full100 comparison')
p.add_argument('--document-ids', default='', help='Explicit dedicated development subset IDs; never select by held-out question results')
p.add_argument('--rates', default='0,1,5,20,100')
p.add_argument('--max-seconds', type=int, default=7200)
p.add_argument('--embedding-batch-size', type=int, default=8)
p.add_argument('--embedding-timeout', type=int, default=120)
a = p.parse_args()
rates = [int(v) for v in a.rates.split(',')]
if not 1 <= a.max_documents <= 100 or not rates or len(set(rates)) != len(rates) or any(v not in [0, 1, 5, 20, 100] for v in rates):
    raise SystemExit('Expected 1..100 documents and distinct frozen change rates')
if a.max_seconds < 1 or not 1 <= a.embedding_batch_size <= 256 or not 1 <= a.embedding_timeout <= 180:
    raise SystemExit('Invalid bounded run/model request configuration')
if a.output.exists() or a.output.with_suffix('.frozen').exists():
    raise SystemExit('Fresh result stem required; do not overwrite failed runs')
a.output.parent.mkdir(parents=True, exist_ok=True)
frozen = a.output.with_suffix('.frozen'); frozen.mkdir()
helper = frozen / 'RawHashEfficiencyProbe.java'
helper.write_bytes((R / 'scripts/RawHashEfficiencyProbe.java').read_bytes())
manifest_bytes = (a.fixtures / 'manifest.json').read_bytes()
manifest = json.loads(manifest_bytes)
plan = json.loads(a.input_plan.read_text())
jar_sha = hashlib.sha256(a.jar.read_bytes()).hexdigest()
if plan.get('status') != 'PASS_FROZEN_ACTUAL_HASH_INPUTS_NO_INFERENCE' or plan.get('jarSha256') != jar_sha or plan.get('manifestSha256') != hashlib.sha256(manifest_bytes).hexdigest():
    raise SystemExit('Actual frozen native input plan must match this exact JAR and corpus')
docs = manifest['efficiency']['documents']
if len(docs) != 100 or len(plan['documents']) != 100:
    raise SystemExit('Expected frozen 100-document development corpus')
selected_ids = a.document_ids.split(',') if a.document_ids else []
if selected_ids and (len(selected_ids) != a.max_documents or len(set(selected_ids)) != len(selected_ids) or not set(selected_ids).issubset({d['id'] for d in docs})):
    raise SystemExit('Explicit subset must contain exactly max-documents distinct frozen development IDs')
for doc in docs:
    for original in [doc['baseline']] + [v['original'] for v in doc['versions']]:
        data = (a.fixtures / original['path']).read_bytes()
        if len(data) != original['sizeBytes'] or hashlib.sha256(data).hexdigest() != original['sha256']:
            raise SystemExit('Frozen source changed: ' + doc['id'])
tokenizer = json.loads(a.tokenizer_manifest.read_text())
assets = {name: hashlib.sha256((a.tokenizer_manifest.parent / name).read_bytes()).hexdigest() for name in tokenizer['files']}
if assets != tokenizer['files']:
    raise SystemExit('Frozen tokenizer asset mismatch')
settings = dict(line.split('=', 1) for line in (R / '.env.target').read_text().splitlines() if line and not line.startswith('#'))
upstream = settings['EKB_EMBEDDING_BASE_URL'].rstrip('/')
if not upstream.startswith(('http://127.0.0.1:', 'http://localhost:')):
    raise SystemExit('Benchmark permits existing local shared embedding endpoint only')
prefix = 'raw_hashbench_' + uuid.uuid4().hex[:10]
namespaces = {scheme: prefix + '_' + scheme.lower() for scheme in ['FULL', 'HASH']}
context_path = a.output.with_suffix('.context.json')
context_path.write_text('{}')
trace_path = a.output.with_suffix('.http.jsonl')
binding = {'artifact': str(a.jar.resolve()), 'jarSha256': jar_sha,
    'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(), 'inputPlanSha256': hashlib.sha256(a.input_plan.read_bytes()).hexdigest(),
    'helperSourceSha256': hashlib.sha256(helper.read_bytes()).hexdigest(), 'tokenizerAssetSha256': assets,
    'modelRevision': tokenizer['modelRevision'], 'tokenizerRevision': tokenizer['tokenizerRevision'],
    'postgresSchemas': namespaces, 'milvusDatabases': namespaces,
    'mode': 'REAL_WHOLE_RAW_PORT' if a.real_embedding else 'FIXTURE_RUNNER_ONLY',
    'startedAt': datetime.now(timezone.utc).isoformat(), 'provenance': 'IMMUTABLE_JAR_CLASSES_AND_MIGRATIONS_NO_PRODUCTION_OVERLAY',
    'originalsPreflightVerified': 600, 'downstreamServingEndpoint': upstream,
    'fixtureResultsCannotClaimBenefit': True, 'stageDeadlineSecondsUnchanged': 600}
binding['runnerSourceSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
binding['atomicValidatorSourceSha256'] = hashlib.sha256((R / 'scripts/hash_encoding_identity.py').read_bytes()).hexdigest()
def serving_native_identity():
    row = json.loads(subprocess.check_output(['docker', 'inspect', 'embedding-model'], text=True, timeout=10))[0]
    return {'containerId': row['Id'], 'imageId': row['Image'],
            'sourceDigest': (row['Config'].get('Labels') or {}).get('orbisops.retrieval.source'),
            'restartCount': row['RestartCount'], 'processStartedAt': row['State']['StartedAt'],
            'hostProcessPid': row['State']['Pid']}
records = []
trace_lock = threading.Lock()
active_requests = 0
atomic_expected = None

class TraceHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        global active_requests
        if self.path != '/v1/embeddings':
            self.send_error(404); return
        raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        try:
            body = json.loads(raw)
            inputs = body['input'] if isinstance(body['input'], list) else [body['input']]
            context = json.loads(context_path.read_text())
        except Exception as error:
            self.send_error(400, 'Invalid probe request/context'); return
        record = {'sequence': 0, 'context': context, 'path': self.path,
            'requestedModel': body.get('model'), 'requestedDimensions': body.get('dimensions'),
            'attemptedInputCount': len(inputs), 'fullInputSha256': [hashlib.sha256(t.encode()).hexdigest() for t in inputs],
            'requestBodySha256': hashlib.sha256(raw).hexdigest(), 'requestBytes': len(raw),
            'startedAt': datetime.now(timezone.utc).isoformat()}
        with trace_lock:
            active_requests += 1; record['sequence'] = len(records) + 1; records.append(record)
        start = time.monotonic()
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ['host', 'content-length', 'connection']}
        req = urllib.request.Request(upstream + self.path, data=raw, headers=headers, method='POST')
        payload = b''; code = 502
        try:
            response = urllib.request.urlopen(req, timeout=a.embedding_timeout + 5)
            code = response.status; payload = response.read()
        except urllib.error.HTTPError as error:
            code = error.code; payload = error.read()
        except Exception as error:
            record['transportFailure'] = type(error).__name__ + ': ' + str(error)
            payload = json.dumps({'error': {'message': 'Probe trace upstream transport failed', 'type': 'probe_transport'}}).encode()
        record.update(httpStatus=code, responseBodySha256=hashlib.sha256(payload).hexdigest(), responseBytes=len(payload),
            elapsedMillis=(time.monotonic() - start) * 1000, finishedAt=datetime.now(timezone.utc).isoformat())
        if code >= 400:
            record['actualErrorResponse'] = payload.decode(errors='replace')[:8000]
        client_code, client_payload = code, payload
        if code == 200 and a.real_embedding:
            # Preserve actual upstream cost/status; only certified vectors may reach the cache.
            try:
                decoded = json.loads(payload)
                validate_atomic_response(decoded, inputs, tokenizer['model'], tokenizer['modelRevision'],
                                         expected=atomic_expected)
                record['atomicEncodingIdentity'] = decoded['encoding_identity']
                record['atomicCertificationAccepted'] = True
            except Exception as error:
                record['atomicCertificationAccepted'] = False
                record['atomicCertificationFailure'] = type(error).__name__ + ': ' + str(error)
                evidence_path = a.output.with_suffix('.uncertified-response-' + str(record['sequence']) + '.json')
                evidence_path.write_bytes(payload)
                record['actualUncertifiedResponsePath'] = str(evidence_path)
                client_code = 502
                client_payload = json.dumps({'error': {'message': 'Atomic encoder identity rejected by benchmark guard',
                                                       'type': 'benchmark_identity'}}).encode()
        record['clientHttpStatus'] = client_code
        record['clientResponseBodySha256'] = hashlib.sha256(client_payload).hexdigest()
        try:
            self.send_response(client_code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(client_payload)))
            self.end_headers(); self.wfile.write(client_payload)
        except (BrokenPipeError, ConnectionResetError):
            record['clientDisconnectedBeforeResponse'] = True
        finally:
            with trace_lock:
                active_requests -= 1
                with trace_path.open('a') as log:
                    log.write(json.dumps(record, ensure_ascii=False) + '\n')

server = None
try:
    if a.real_embedding:
        binding['actualServingNativeBefore'] = serving_native_identity()
        with urllib.request.urlopen(upstream + '/ready', timeout=5) as response:
            readiness = json.load(response)
        binding['actualServingReadinessBefore'] = readiness
        binding['runtimeRevisionContractPresent'] = 'embeddingModelRevision' in readiness
        if readiness.get('embeddingModel') != tokenizer['model'] or not readiness.get('ready'):
            raise RuntimeError('Actual serving not ready for the frozen embedding model')
        if 'embeddingModelRevision' in readiness and readiness['embeddingModelRevision'] != tokenizer['modelRevision']:
            raise RuntimeError('Actual model revision differs from frozen manifest')
        if readiness.get('inference', {}).get('busy'):
            raise RuntimeError('Shared encoder busy; no preflight or benchmark inference was submitted')
        # Native capability may be 2048; this experiment requests and validates actual 1024 outputs.
        binding['requestedActualOutputDimensions'] = 1024
        # A previous rerank may have evicted embedding. An owned complete DEV input
        # certifies the real encoder on admission; this extra cost is never free.
        preflight_id = selected_ids[0] if selected_ids else docs[0]['id']
        preflight_input = next(v for v in plan['documents'] if v['documentId'] == preflight_id)['baseline']['inputs'][0]
        text = preflight_input['fullInput']
        if hashlib.sha256(text.encode()).hexdigest() != preflight_input['fullInputSha256']:
            raise RuntimeError('Frozen complete preflight input identity mismatch')
        request_body = json.dumps({'model': tokenizer['model'], 'dimensions': 1024,
                                  'input_type': 'document', 'input': [text]}, ensure_ascii=False).encode()
        headers = {'Content-Type': 'application/json', 'X-EvoKB-Model-Purpose': 'evokb_hash_benchmark_preflight'}
        if settings.get('EKB_EMBEDDING_API_KEY'):
            headers['Authorization'] = 'Bearer ' + settings['EKB_EMBEDDING_API_KEY']
        preflight = {'fixtureDocumentId': preflight_id, 'fullInputSha256': preflight_input['fullInputSha256'],
                     'fullInputNativeTokens': preflight_input['tokenCount'], 'requestBodySha256': hashlib.sha256(request_body).hexdigest(),
                     'actualModelHttpAttempts': 1, 'attemptedInputCount': 1, 'acceptedVectorCount': 0,
                     'startedAt': datetime.now(timezone.utc).isoformat(), 'scope': 'SEPARATE_REAL_ENCODING_PROFILE_PREFLIGHT_COST'}
        binding['encodingProfilePreflight'] = preflight
        # Persist attempted cost even if the kernel/client fails before returning.
        a.output.with_suffix('.preflight.json').write_text(json.dumps(preflight, ensure_ascii=False, indent=2))
        preflight_started = time.monotonic()
        try:
            request = urllib.request.Request(upstream + '/v1/embeddings', data=request_body, headers=headers, method='POST')
            try:
                with urllib.request.urlopen(request, timeout=a.embedding_timeout + 5) as response:
                    preflight_code, preflight_payload = response.status, response.read()
            except urllib.error.HTTPError as error:
                preflight_code, preflight_payload = error.code, error.read()
            preflight['actualHttpStatus'] = preflight_code
            preflight['responseBodySha256'] = hashlib.sha256(preflight_payload).hexdigest()
            a.output.with_suffix('.preflight-response.json').write_bytes(preflight_payload)
            if preflight_code != 200:
                raise RuntimeError('Actual encoder profile preflight did not return 200')
            decoded = json.loads(preflight_payload)
            atomic_expected = validate_atomic_response(decoded, [text], tokenizer['model'], tokenizer['modelRevision'])
            preflight['acceptedVectorCount'] = 1
            preflight['atomicEncodingIdentity'] = decoded['encoding_identity']
            binding['actualAtomicServingProfile'] = atomic_expected
            binding['revisionVerificationBoundary'] = 'Loaded revision and all encoding profile fields verified in each own atomic response; ready is not certification'
        except Exception as error:
            preflight['failure'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            preflight['elapsedMillis'] = (time.monotonic() - preflight_started) * 1000
            preflight['finishedAt'] = datetime.now(timezone.utc).isoformat()
            a.output.with_suffix('.preflight.json').write_text(json.dumps(preflight, ensure_ascii=False, indent=2))
        server = ThreadingHTTPServer(('127.0.0.1', 0), TraceHandler)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        endpoint = 'http://127.0.0.1:' + str(server.server_address[1])
        binding['transparentTraceEndpoint'] = endpoint
    else:
        endpoint = upstream
        binding['actualModelRequests'] = 0
    with zipfile.ZipFile(a.jar) as archive:
        migrations = {Path(name).name: archive.read(name) for name in archive.namelist() if name.startswith('BOOT-INF/classes/db/migration/V') and name.endswith('.sql')}
    binding['migrationSha256'] = {name: hashlib.sha256(data).hexdigest() for name, data in migrations.items()}
    for scheme, namespace in namespaces.items():
        sql = f'CREATE SCHEMA {namespace};SET search_path TO {namespace};\n' + ''.join(migrations[name].decode() for name in sorted(migrations, key=lambda name: int(name.split('__')[0][1:])))
        a.output.with_suffix('.' + scheme.lower() + '.seed.sql').write_text(sql)
        applied = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', '-'], input=sql, text=True, capture_output=True, timeout=90)
        a.output.with_suffix('.' + scheme.lower() + '.seed.log').write_text(applied.stdout + applied.stderr); applied.check_returncode()
    env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'], PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'], PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'],
        PROBE_EMBEDDING_BASE_URL=endpoint, PROBE_EMBEDDING_API_KEY=settings.get('EKB_EMBEDDING_API_KEY', ''),
        PROBE_EMBEDDING_BATCH_SIZE=str(a.embedding_batch_size), PROBE_EMBEDDING_TIMEOUT=str(a.embedding_timeout),
        PROBE_ACTUAL_SERVING_ENDPOINT=upstream,
        PROBE_ATOMIC_PROFILE_SHA256=atomic_expected['profile_sha256'] if atomic_expected else '')
    for scheme, namespace in namespaces.items():
        env['PROBE_' + scheme + '_JDBC_URL'] = 'jdbc:postgresql://127.0.0.1:15465/evokb_target?currentSchema=' + namespace + '&stringtype=unspecified'
    with tempfile.TemporaryDirectory(prefix='evokb-hash-efficiency-native-') as directory:
        work = Path(directory); classes = work / 'classes'; classes.mkdir(); libraries = []; hashes = {}
        with zipfile.ZipFile(a.jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    target = work / Path(name).name; data = archive.read(name); target.write_bytes(data); libraries.append(target); hashes[target.name] = hashlib.sha256(data).hexdigest()
        binding['dependencySha256'] = hashes
        cp = os.pathsep.join(str(path) for path in libraries)
        compiled = subprocess.run([str(JAVA / 'javac'), '-J-Xmx128m', '-J-XX:ActiveProcessorCount=2', '-cp', cp, '-d', str(classes), str(helper)], capture_output=True, text=True, timeout=90)
        a.output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr); compiled.check_returncode()
        binding['helperClassSha256'] = {str(path.relative_to(classes)): hashlib.sha256(path.read_bytes()).hexdigest() for path in classes.rglob('*.class')}
        log_path = a.output.with_suffix('.runtime.log')
        with log_path.open('w') as log:
            ran = subprocess.run([str(JAVA / 'java'), '-Xmx256m', '-XX:ActiveProcessorCount=2', '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]', '-cp', str(classes) + os.pathsep + cp,
                'cn.lgs.knowledgebase.application.raw.RawHashEfficiencyProbe', str(a.output), str(a.fixtures), str(a.tokenizer_manifest), prefix, str(context_path), 'real' if a.real_embedding else 'fixture',
                str(a.max_documents), a.rates, str(a.max_seconds), str(a.input_plan), a.document_ids], stdout=log, stderr=subprocess.STDOUT, env=env, timeout=a.max_seconds + 600)
        binding['processExitCode'] = ran.returncode
except Exception as error:
    binding['failure'] = (type(error).__name__ + ': ' + (str(error.returncode) if isinstance(error, subprocess.CalledProcessError) else str(error)))
finally:
    if server is not None:
        server.shutdown(); server.server_close()
        deadline = time.monotonic() + 10
        while active_requests and time.monotonic() < deadline:
            time.sleep(0.1)
        binding['traceRequestsStillInFlightAtEnd'] = active_requests
        binding['actualModelHttpRequests'] = len(records)
        binding['actualHttpAttemptedInputsIncludingRetries'] = sum(r['attemptedInputCount'] for r in records)
        binding['actualHttpStatusCounts'] = {str(code): sum(r.get('httpStatus') == code for r in records) for code in sorted(set(r.get('httpStatus', 0) for r in records))}
    binding['actualModelHttpRequestsIncludingPreflight'] = len(records) + binding.get('encodingProfilePreflight', {}).get('actualModelHttpAttempts', 0)
    binding['actualHttpAttemptedInputsIncludingPreflight'] = sum(r['attemptedInputCount'] for r in records) + binding.get('encodingProfilePreflight', {}).get('attemptedInputCount', 0)
    if a.real_embedding:
        try:
            binding['actualServingNativeAfter'] = serving_native_identity()
        except Exception as error:
            binding['servingNativeReadbackFailure'] = str(error)
report = json.loads(a.output.read_text()) if a.output.exists() else {'status': 'FAIL_OR_INCOMPLETE_HASH_COMPARISON'}
if not a.output.exists() and Path(str(a.output) + '.checkpoint.json').exists():
    report['runs'] = json.loads(Path(str(a.output) + '.checkpoint.json').read_text())
report.update(binding)
if binding.get('processExitCode') != 0 or binding.get('traceRequestsStillInFlightAtEnd', 0):
    report['status'] = 'FAIL_OR_INCOMPLETE_HASH_COMPARISON'
if a.real_embedding:
    atomic_checks = {
        'profilePreflightActuallyCertifiedAndCountedSeparately': binding.get('encodingProfilePreflight', {}).get('acceptedVectorCount') == 1,
        'allSuccessfulHttpResponsesAtomicallyCertified': all(r.get('atomicCertificationAccepted') is True for r in records if r.get('httpStatus') == 200),
        'noIdentityGuardRejectedActualResponses': all(r.get('httpStatus') != 200 or r.get('clientHttpStatus') == 200 for r in records),
        'sameActualServingContainerImageSourceAndProcess': binding.get('actualServingNativeBefore') == binding.get('actualServingNativeAfter') and bool(binding.get('actualServingNativeBefore')),
        'nativeCacheProfileContainsTheAtomicRuntimeIdentity': bool(atomic_expected) and report.get('atomicServingProfileSha256') == atomic_expected['profile_sha256']}
    for run in report.get('runs', []):
        expected_pairs = [(h, v) for call in run.get('actualBackendCalls', []) if call.get('acceptedVectorCount')
                          for h, v in zip(call['fullInputSha256'], call['actualReturnedVectorFloat32Sha256'])]
        actual_pairs = [(proof['input_sha256'], proof['vector_sha256']) for record in records
                        if record.get('atomicCertificationAccepted') and record['context'].get('scheme') == run['scheme']
                        and record['context'].get('fixtureDocumentId') == run['fixtureDocumentId']
                        and record['context'].get('changeRate') == run['changeRate']
                        for proof in record['atomicEncodingIdentity']['inputs']]
        run['acceptedNativeVectorsMatchOwnAtomicHttpPairs'] = expected_pairs == actual_pairs
    atomic_checks['everyNativeAcceptedVectorMatchesOwnAtomicHttpInputAndFloat32Hash'] = all(r.get('acceptedNativeVectorsMatchOwnAtomicHttpPairs') for r in report.get('runs', []))
    report['atomicIdentityChecks'] = atomic_checks
    if not all(atomic_checks.values()):
        report['status'] = 'FAIL_OR_INCOMPLETE_HASH_COMPARISON'
if a.fixture_vectors:
    report['efficiencyClaim'] = False
    report['actualModelRequests'] = 0
report['finishedAt'] = datetime.now(timezone.utc).isoformat()
report['totalWallMillisIncludingPreflight'] = (datetime.now(timezone.utc) - datetime.fromisoformat(binding['startedAt'])).total_seconds() * 1000
report['runnerAndValidatorSourceStableDuringRun'] = (binding['runnerSourceSha256'] == hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    and binding['atomicValidatorSourceSha256'] == hashlib.sha256((R / 'scripts/hash_encoding_identity.py').read_bytes()).hexdigest())
if not report['runnerAndValidatorSourceStableDuringRun']:
    report['status'] = 'FAIL_OR_INCOMPLETE_HASH_COMPARISON'
metrics = []
for scheme in ['FULL', 'HASH']:
    for rate in ['baseline'] + rates:
        runs = [r for r in report.get('runs', []) if r['scheme'] == scheme and r['changeRate'] == rate]
        passed = [r for r in runs if r.get('status') == 'PASS']
        times = sorted(r['componentEndToEndSearchableMillis'] for r in passed)
        metrics.append({'scheme': scheme, 'changeRate': rate, 'executedDocuments': len(runs), 'passedDocuments': len(passed),
            'failedDocuments': len(runs) - len(passed), 'actualAcceptedEncoderInputs': sum(r.get('acceptedEncoderInputs', 0) for r in runs),
            'actualAcceptedNativeInputTokens': sum(r.get('actualAcceptedInputTokens', 0) for r in runs),
            'totalComponentSearchableMillisSuccessfulSubset': sum(times),
            'p95ComponentSearchableMillisSuccessfulSubset': times[min(len(times) - 1, int(len(times) * 0.95))] if times else None,
            'actualObservedHttpRequests': sum(r['context'].get('scheme') == scheme and r['context'].get('changeRate') == rate for r in records),
            'actualHttpAttemptedInputsIncludingRetries': sum(r['attemptedInputCount'] for r in records if r['context'].get('scheme') == scheme and r['context'].get('changeRate') == rate),
            'inference': 'REAL_MODEL' if a.real_embedding else 'FIXTURE_ONLY_NOT_MEASURED_BENEFIT'})
report['metrics'] = metrics
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
summary = {k: report.get(k) for k in ['status', 'checks', 'executedSourceRuns', 'requestedNativeSourceRuns', 'realModelInference', 'officialHash100Complete', 'actualModelHttpRequests', 'actualModelRequests', 'failure', 'postgresSchemas', 'milvusDatabases']}
summary['metrics'] = metrics
a.output.with_suffix('.summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)); print(json.dumps(summary, ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') and binding.get('processExitCode') == 0 else 1)
