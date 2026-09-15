#!/usr/bin/env python3
"""Run frozen questions via normal asynchronous APIs; preserve failures and native evidence per attempt."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
TERMINAL = {'ANSWERED', 'INSUFFICIENT', 'FAILED', 'WAITING_USER'}


def now():
    return datetime.now(timezone.utc).isoformat()


def retrieval_service_snapshot():
    """Bind a campaign to the actual local image/runtime without loading a model."""
    result = subprocess.run(['docker', 'inspect', 'embedding-model'],
                            text=True, capture_output=True, timeout=30, check=True)
    rows = json.loads(result.stdout)
    if len(rows) != 1 or not rows[0]['State']['Running']:
        raise ValueError('The ordinary local retrieval container must be running')
    container = rows[0]
    allowlist = {'EMBEDDING_MODEL_ID', 'RERANK_MODEL_ID', 'EMBEDDING_DIMENSIONS',
                 'MODEL_DEVICE', 'INFERENCE_CPU_THREADS', 'INFERENCE_INTEROP_THREADS',
                 'INFERENCE_CPU_ATTENTION', 'HF_HUB_OFFLINE', 'TRANSFORMERS_OFFLINE'}
    settings = {key: value for entry in container['Config'].get('Env', [])
                for key, _, value in [entry.partition('=')] if key in allowlist}
    if (settings.get('EMBEDDING_MODEL_ID') != 'Qwen/Qwen3-VL-Embedding-2B'
            or settings.get('RERANK_MODEL_ID') != 'Qwen/Qwen3-VL-Reranker-2B'):
        raise ValueError('Use the approved Qwen models in the existing retrieval container')
    return dict(containerId=container['Id'], imageId=container['Image'],
                startedAt=container['State']['StartedAt'], restartCount=container['RestartCount'],
                settings=settings,
                boundary='Read-only Docker identity; no loading, embedding or reranking. '
                         'Container image and allowlisted execution settings are frozen. '
                         'This does not retroactively certify individual historical vectors.')


def canonical(text):
    text = re.sub(r'(\d{4})年(\d{1,2})月(\d{1,2})日',
                  lambda m: '%s-%02d-%02d' % (m[1], int(m[2]), int(m[3])), text)
    text = re.sub(r'(\d{4})-(\d{1,2})-(\d{1,2})',
                  lambda m: '%s-%02d-%02d' % (m[1], int(m[2]), int(m[3])), text)
    return re.sub(r'[\s,*_`]', '', text)


def asserted(text, phrase):
    """Conservative lexical check only; never labels this a universal semantic judge."""
    text, phrase = canonical(text), canonical(phrase)
    for match in re.finditer(re.escape(phrase), text):
        before = re.split(r'[。；;，,！？\n]', text[max(0, match.start() - 12):match.start()])[-1]
        if not re.search(r'(无法|不能|没有|不证明|未确认|不代表|并非|尚未|未|不)', before):
            return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--condition', choices=['baseline', 'layered'], required=True)
    parser.add_argument('--case', action='append', default=[])
    parser.add_argument('--held-complex-only', action='store_true')
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--observe-seconds', type=int, default=1800)
    args = parser.parse_args()
    if not 1 <= args.repeats <= 5 or not 600 <= args.observe_seconds <= 3600:
        raise ValueError('Fixed bounded observation/repetition required; no backend deadline changes')
    manifest = json.loads(args.manifest.read_text())
    selected = [case for case in manifest['cases'] if not args.case or case['caseId'] in args.case]
    if args.held_complex_only:
        selected = [case for case in selected if case['category'] == 'complex' and case['split'] == 'holdout']
        if len(selected) != 13:
            raise ValueError('Held complex slice must contain exactly the frozen thirteen questions')
    if not selected or any(case_id not in {case['caseId'] for case in selected} for case_id in args.case):
        raise ValueError('Unknown or empty frozen case selection')
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    process = json.loads((ROOT / '.tmp/target/process.json').read_text())
    if hashlib.sha256(Path(process['artifact']).read_bytes()).hexdigest() != process['jarSha256']:
        raise ValueError('Immutable JAR identity changed')
    spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    accounts = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())
    clients = {key: module.client(accounts[key])[0] for key in {case['account'] for case in selected}}

    def sql_observe(sql, destination):
        destination.with_suffix('.sql').write_text('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' + sql + '\nCOMMIT;\n')
        result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                                 '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                                input=destination.with_suffix('.sql').read_text(), text=True,
                                capture_output=True, timeout=30)
        destination.with_suffix('.log').write_text(result.stdout + result.stderr)
        result.check_returncode()
        return json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))

    def source_snapshot(destination):
        filenames = sorted({name for case in selected for name in case['scopeFilenames']})
        literals = ','.join("'" + name.replace("'", "''") + "'" for name in filenames)
        return sql_observe("""SELECT jsonb_build_object(
 'sources',(SELECT coalesce(jsonb_agg(s ORDER BY s.original_filename),'[]') FROM (SELECT d.document_id,
   v.version_id,v.original_filename,v.sha256,v.lifecycle,m.security_level,m.acl_version
   FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id)
   WHERE v.original_filename IN (%s)) s),
 'derived',(SELECT coalesce(jsonb_agg(c ORDER BY c.asset_id,c.asset_version),'[]') FROM kb_asset_current c
   WHERE EXISTS(SELECT 1 FROM kb_asset_source a JOIN document_version v ON v.version_id=a.source_version_id
     WHERE a.asset_id=c.asset_id AND a.asset_version=c.asset_version AND v.original_filename IN (%s))),
 'graph',(SELECT coalesce(jsonb_agg(r ORDER BY r.relation_id,r.revision_id),'[]') FROM kb_graph_relation r
   WHERE r.is_active AND EXISTS(SELECT 1 FROM kb_graph_evidence g JOIN evidence e USING(evidence_id)
     JOIN document_version v ON v.version_id=e.version_id WHERE g.kb_id=r.kb_id AND g.relation_id=r.relation_id
       AND g.revision_id=r.revision_id AND v.original_filename IN (%s))));
""" % (literals, literals, literals), destination)

    def runtime_snapshot(destination):
        private = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                       if line and not line.startswith('#'))
        defaults = dict(EKB_QUERY_MAX_RETRIEVAL_ROUNDS='8', EKB_QUERY_MAX_TOTAL_QUERIES='8',
                        EKB_QUERY_MAX_QUERIES_PER_ROUND='3', EKB_QUERY_CONTEXT_CHARS='24000',
                        EKB_QUERY_EVIDENCE_CONTEXT_CHARS='12000')
        # Only allowlisted nonsecret settings leave the private file.
        budgets = {key: int(private.get(key, default)) for key, default in defaults.items()}
        if budgets['EKB_QUERY_MAX_RETRIEVAL_ROUNDS'] != 8 or budgets['EKB_QUERY_MAX_TOTAL_QUERIES'] != 8:
            raise ValueError('Frozen formal retrieval budget must remain eight rounds/queries')
        path = Path(private['EKB_EMBED_TOKENIZER_MANIFEST'])
        native = sql_observe("""SELECT jsonb_build_object('activeProfiles',
          (SELECT coalesce(jsonb_agg(p),'[]') FROM (SELECT profile_id,name,small_model,large_model,
           chat_timeout_seconds,embedding_base_url,embedding_model,embedding_dimensions,
           embedding_batch_size,embedding_timeout_seconds,rerank_base_url,rerank_model,
           rerank_timeout_seconds,updated_at FROM model_configuration_profile WHERE active) p));""", destination)
        profiles = native['activeProfiles']
        if len(profiles) != 1 or profiles[0]['small_model'] != 'gpt-5.6-luna' or profiles[0]['large_model'] != 'gpt-5.6-terra':
            raise ValueError('Approved model roles and one active profile are required')
        profile = profiles[0]
        if profile['embedding_base_url'] != 'http://127.0.0.1:8110' or profile['rerank_base_url'] != 'http://127.0.0.1:8110' or profile['embedding_dimensions'] != 1024:
            raise ValueError('Use the ordinary approved local retrieval service, not a fault profile')
        return dict(profile=profile, budgets=budgets, retrievalService=retrieval_service_snapshot(),
                    tokenizerManifestSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                    contextMaxSources=8, contextConservativeFrozenTokenBudget=6000,
                    tokenAccounting='Frozen Qwen full encoder-input count per whole evidence, including '
                                    'its template overhead; not GPT provider prompt usage')

    frozen_runtime = runtime_snapshot(output / 'frozen-runtime')

    frozen = source_snapshot(output / 'frozen-sources')
    filenames = {name for case in selected for name in case['scopeFilenames']}
    declared = {doc['filename']: doc for doc in manifest['documents']}
    source_map = {}
    for filename in filenames:
        rows = [row for row in frozen['sources'] if row['original_filename'] == filename]
        if len(rows) != 1 or rows[0]['lifecycle'] != 'ACTIVE' or rows[0]['sha256'] != declared[filename]['sha256'] or rows[0]['security_level'] != declared[filename]['securityLevel']:
            raise ValueError('Every selected original must be actually ACTIVE with frozen hash and authority: ' + filename)
        source_map[filename] = rows[0]
    if args.condition == 'baseline' and (frozen['derived'] or frozen['graph']):
        raise ValueError('Raw-only baseline requires zero formal-source derived publication; no flag bypass')
    if args.condition == 'layered' and not frozen['derived']:
        raise ValueError('Layered condition requires actual derived publication before freezing')
    report = dict(status='RUNNING_ACTUAL_FORMAL_QUERIES', startedAt=now(), condition=args.condition,
                  manifestSha256=hashlib.sha256(args.manifest.read_bytes()).hexdigest(), process=process,
                  runnerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), sourceSnapshot=frozen,
                  runtimeSnapshot=frozen_runtime,
                  declaredCases=[case['caseId'] for case in selected], plannedAttempts=len(selected) * args.repeats,
                  attempts=[],
                  boundary='Normal natural-language question APIs, real approved models, immutable source '
                           'hashes and read-only PG. Gold is never passed to the model. No download, feedback, '
                           'heat or adaptive prewarming. Baseline and layered are ordinary deployed retrieval '
                           'with the same budgets; layered publication must be independently achieved then frozen. '
                           'Strict lexical facts and evidence checks are a conservative automatic score, '
                           'not human adjudication, Ragas, old 5.1/1.8 or an invented model-quality result. '
                           'Failure/timeout/clarification remains in the denominator; unstarted is separate.')

    def persist():
        counts = Counter(attempt.get('outcome', 'IN_FLIGHT') for attempt in report['attempts'])
        report['counts'] = dict(counts)
        report['unstartedAttempts'] = report['plannedAttempts'] - len(report['attempts'])
        (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    persist()
    try:
        for repeat in range(1, args.repeats + 1):
            for case in selected:
                if json.loads((ROOT / '.tmp/target/process.json').read_text()) != process:
                    raise ValueError('Deployment changed; do not mix running artifacts into this frozen campaign')
                current = source_snapshot(output / ('sources-before-' + case['caseId'] + '-r' + str(repeat)))
                if current != frozen:
                    raise ValueError('Source, ACL or derived publication changed; preserve partial evaluation')
                if runtime_snapshot(output / ('runtime-before-' + case['caseId'] + '-r' + str(repeat))) != frozen_runtime:
                    raise ValueError('Active model profile, tokenizer or budgets changed; preserve the partial campaign')
                request = clients[case['account']]
                attempt = dict(caseId=case['caseId'], repeat=repeat, split=case['split'],
                               category=case['category'], startedAt=now(), observations=[])
                report['attempts'].append(attempt)
                persist()
                started = time.monotonic()
                code, result = request('/query', dict(question=case['question'], limit=8,
                    filters=dict(version_ids=[source_map[name]['version_id'] for name in case['scopeFilenames']])))
                attempt['startHttpStatus'] = code
                if code != 200:
                    attempt.update(outcome='FAIL_START', final=result, finishedAt=now(), elapsedSeconds=time.monotonic() - started)
                    persist()
                    continue
                session = str(uuid.UUID(result['session_id']))
                attempt['sessionId'] = session
                deadline, previous = time.monotonic() + args.observe_seconds, None
                while time.monotonic() < deadline:
                    code, result = request('/query/session?id=' + session)
                    state = result.get('status')
                    if code != 200:
                        raise ValueError('Owned normal session became unreadable')
                    if state != previous:
                        attempt['observations'].append(dict(at=now(), status=state))
                        previous = state
                        persist()
                    if state in TERMINAL:
                        break
                    time.sleep(3)
                else:
                    attempt.update(outcome='FAIL_OBSERVATION_TIMEOUT', final=result, finishedAt=now())
                    persist()
                    raise TimeoutError('Actual query remains in flight; stop new admissions without altering its state')
                native = sql_observe(f"""SELECT jsonb_build_object(
 'session',(SELECT to_jsonb(s) FROM query_session s WHERE session_id='{session}'),
 'reservations',(SELECT coalesce(jsonb_agg(r),'[]') FROM query_retrieval_reservation r WHERE session_id='{session}'),
 'rounds',(SELECT coalesce(jsonb_agg(r),'[]') FROM retrieval_round r WHERE session_id='{session}'),
 'evidence',(SELECT coalesce(jsonb_agg(e),'[]') FROM (SELECT q.evidence_id,q.first_seen_round,
   e.version_id,v.original_filename,a.security_level,a.acl_version FROM query_evidence q
   JOIN evidence e USING(evidence_id) JOIN document_version v ON v.version_id=e.version_id
   LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE q.session_id='{session}') e),
 'dependencies',(SELECT coalesce(jsonb_agg(d),'[]') FROM (SELECT q.version_id,a.security_level,a.acl_version
   FROM query_source_dependency q LEFT JOIN kb_source_authority a USING(version_id) WHERE q.session_id='{session}') d),
 'sourceCredits',(SELECT count(*) FROM source_usage_contribution WHERE session_id='{session}'),
 'entryCredits',(SELECT count(*) FROM asset_usage_contribution WHERE session_id='{session}'));
""", output / (case['caseId'] + '-r' + str(repeat)))
                answer = canonical(result.get('answer', ''))
                citations = result.get('citations', [])
                checks = {
                    'ownedNativeSessionAndStatus': native['session']['user_id'] == accounts[case['account']]['user_id'] and native['session']['status'] == result['status'],
                    'expectedTerminalStatus': result['status'] in case['expectedStatus'],
                    'answerEqualsSavedNativeText': result.get('answer') == native['session']['final_answer'],
                    'budgetAtMostEightUniqueReservations': len(native['reservations']) <= 8 and len({r['round_no'] for r in native['reservations']}) == len(native['reservations']) and all(1 <= r['round_no'] <= 8 for r in native['reservations']),
                    'completedRoundsHaveActualReservedIdentity': all(r['round_id'] in {s['reservation_id'] for s in native['reservations']} for r in native['rounds']),
                    'allCitationsBelongToNativeEvidenceAndDeclaredScope': all(c['evidence']['evidence_id'] in {e['evidence_id'] for e in native['evidence']} and c['evidence']['version_id'] in {source_map[name]['version_id'] for name in case['scopeFilenames']} for c in citations),
                    'allModelInputsRespectCurrentAccountAuthority': all(e['security_level'] is not None and e['security_level'] <= (10 if case['account'] == 'public' else 30) for e in native['evidence'] + native['dependencies']),
                    'noEvaluationManufacturesDownloadHeat': native['sourceCredits'] == native['entryCredits'] == 0,
                    'noForbiddenRestrictedSources': all(c['evidence'].get('source_filename') not in case.get('forbiddenSourceFilenames', []) for c in citations),
                    'noExplicitForbiddenClaims': not any(asserted(answer, phrase) for phrase in case.get('forbiddenClaims', [])),
                }
                for index, fact in enumerate(case['requiredFacts'], 1):
                    checks['fact-%02d-answer' % index] = canonical(fact['text']) in answer
                    if fact['text'] in case.get('derivedFacts', []):
                        checks['fact-%02d-source-evidence' % index] = all(any(c['evidence'].get('source_filename') == name and c['evidence'].get('raw_content') for c in citations) for name in fact['sources'])
                    else:
                        checks['fact-%02d-source-evidence' % index] = any(c['evidence'].get('source_filename') in fact['sources'] and canonical(fact['text']) in canonical(c['evidence'].get('raw_content', '')) for c in citations)
                if case['category'] != 'safety':
                    checks['actualGroundedModelAnswer'] = result['status'] == 'ANSWERED' and not result.get('evidence_only') and bool(citations)
                else:
                    checks['missingOrConflictIsExplicit'] = bool(re.search(r'不足|不记录|未记录|未确认|无法|不能|冲突|争议|没有|未知|不一致|看不到|未提供|未找到|无权|权限', answer))
                attempt.update(outcome='PASS_STRICT_FACTS_AND_EVIDENCE' if all(checks.values()) else 'FAIL_STRICT_FACTS_OR_EVIDENCE',
                               final=result, native=native, checks=checks, finishedAt=now(), elapsedSeconds=time.monotonic() - started,
                               actualConsumedRounds=len(native['reservations']), completedRounds=len(native['rounds']))
                persist()
                print(json.dumps(dict(caseId=case['caseId'], repeat=repeat, status=result['status'],
                                      outcome=attempt['outcome'], rounds=attempt['actualConsumedRounds'],
                                      failedChecks=[key for key, value in checks.items() if not value])), flush=True)
        report['status'] = 'COMPLETED_ACTUAL_FORMAL_QUERIES'
    except Exception as failure:
        report.update(status='PARTIAL_ACTUAL_FORMAL_QUERIES', stopReason=dict(type=type(failure).__name__, message=str(failure)))
    report['finishedAt'] = now()
    persist()
    print(json.dumps(dict(status=report['status'], counts=report['counts'], unstartedAttempts=report['unstartedAttempts'])), flush=True)
    raise SystemExit(int(report['status'] != 'COMPLETED_ACTUAL_FORMAL_QUERIES' or any(not key.startswith('PASS') for key in report['counts'])))


if __name__ == '__main__':
    main()
