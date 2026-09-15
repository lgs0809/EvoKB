#!/usr/bin/env python3
"""Run frozen questions via normal asynchronous APIs; preserve failures and native evidence per attempt."""
import argparse
import copy
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import threading
import time
import uuid

from formal_evidence_scoring import POLICY, contextual_evidence
from formal_text_normalization import canonical, contains_fact, POLICY as FACT_TEXT_POLICY
from formal_source_identity import select_active_source
from formal_retrieval_budget import within_query_budget
from formal_campaign import resume_attempts, requires_execution
from formal_claim_scoring import asserted, POLICY as CLAIM_POLICY
from formal_answer_delivery import terminal_delivery_valid, POLICY as DELIVERY_POLICY

from formal_target import FormalTarget, ROOT, retrieval_service_snapshot
TERMINAL = {'ANSWERED', 'INSUFFICIENT', 'FAILED', 'WAITING_USER'}


def now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    FormalTarget.add_selection(parser)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--condition', choices=['baseline', 'layered'], required=True)
    parser.add_argument('--case', action='append', default=[])
    parser.add_argument('--held-complex-only', action='store_true')
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--observe-seconds', type=int, default=1800)
    parser.add_argument('--workers', type=int, choices=[1, 2], default=1,
                        help='Bound admission to at most two independent sessions')
    parser.add_argument('--resume-from', type=Path,
                        help='Read a retained summary; continue known sessions without new queries')
    args = parser.parse_args()
    target = FormalTarget.selected(args)
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
    process = target.process()
    if hashlib.sha256(Path(process['artifact']).read_bytes()).hexdigest() != process['jarSha256']:
        raise ValueError('Immutable JAR identity changed')
    accounts = target.accounts()
    local = threading.local()

    def owned_client(account):
        if not hasattr(local, 'clients'):
            local.clients = {}
        if account not in local.clients:
            local.clients[account] = target.client(accounts[account])[0]
        return local.clients[account]

    def sql_observe(sql, destination):
        destination.with_suffix('.sql').write_text('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' + sql + '\nCOMMIT;\n')
        result = target.psql(destination.with_suffix('.sql').read_text())
        destination.with_suffix('.log').write_text(result.stdout + result.stderr)
        result.check_returncode()
        return json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))

    def source_snapshot(destination):
        filenames = sorted({name for case in selected for name in case['scopeFilenames']})
        literals = ','.join("'" + name.replace("'", "''") + "'" for name in filenames)
        return sql_observe("""SELECT jsonb_build_object(
 'sources',(SELECT coalesce(jsonb_agg(s ORDER BY s.original_filename,s.version_id),'[]') FROM (SELECT d.document_id,
   v.version_id,v.original_filename,v.sha256,v.lifecycle,d.status document_status,m.security_level,m.acl_version,
   v.parser_pipeline_sha256, p.execution_epoch raw_epoch, p.acl_version raw_acl,
   (SELECT md5(string_agg(pm.physical_id, ',' ORDER BY pm.physical_id))
    FROM raw_publication_member pm WHERE pm.version_id=v.version_id) raw_members_fingerprint
   FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id)
   LEFT JOIN raw_publication p USING(version_id)
   WHERE v.original_filename IN (%s)) s),
 'summaries',(SELECT coalesce(jsonb_agg(s ORDER BY s.summary_id),'[]') FROM
   (SELECT summary_id,version_id,content_hash FROM summary
    JOIN document_version v USING(version_id) WHERE v.original_filename IN (%s)
      AND v.lifecycle='ACTIVE' AND summary.lifecycle='ACTIVE'
      AND grounding_status='PASS' AND coverage_status='PASS') s),
 'derived',(SELECT coalesce(jsonb_agg(a ORDER BY a.asset_id,a.asset_version),'[]') FROM (
   SELECT c.*, (SELECT jsonb_agg(d.source_version_id ORDER BY d.source_version_id)
     FROM kb_asset_dependency d WHERE d.asset_id=c.asset_id AND d.asset_version=c.asset_version) dependencies
   FROM kb_asset_current c
   WHERE EXISTS(SELECT 1 FROM kb_asset_source a JOIN document_version v ON v.version_id=a.source_version_id
     WHERE a.asset_id=c.asset_id AND a.asset_version=c.asset_version AND v.original_filename IN (%s))) a),
 'graph',(SELECT coalesce(jsonb_agg(r ORDER BY r.relation_id,r.revision_id),'[]') FROM kb_graph_relation r
   WHERE r.is_active AND EXISTS(SELECT 1 FROM kb_graph_evidence g JOIN evidence e USING(evidence_id)
     JOIN document_version v ON v.version_id=e.version_id WHERE g.kb_id=r.kb_id AND g.relation_id=r.relation_id
       AND g.revision_id=r.revision_id AND v.original_filename IN (%s))));
""" % (literals, literals, literals, literals), destination)

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
        if len(profiles) != 1 or profiles[0]['small_model'] != 'gpt-6-luna' or profiles[0]['large_model'] != 'gpt-6.1-terra':
            raise ValueError('Approved model roles and one active profile are required')
        profile = profiles[0]
        if profile['embedding_base_url'] != 'http://127.0.0.1:8110' or profile['rerank_base_url'] != 'http://127.0.0.1:8110' or profile['embedding_dimensions'] != 1024:
            raise ValueError('Use the ordinary approved local retrieval service, not a fault profile')
        return dict(profile=profile, budgets=budgets, retrievalService=retrieval_service_snapshot(),
                    tokenizerManifestSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                    queryExecutorThreads=int(private.get('EKB_QUERY_WORKER_THREADS', '2')),
                    contextMaxRetrievedPassages=8, contextConservativeFrozenTokenBudget=6000,
                    tokenAccounting='Frozen Qwen full encoder-input count per whole evidence, including '
                                    'its template overhead; not GPT provider prompt usage')

    frozen_runtime = runtime_snapshot(output / 'frozen-runtime')

    frozen = source_snapshot(output / 'frozen-sources')
    filenames = {name for case in selected for name in case['scopeFilenames']}
    declared = {doc['filename']: doc for doc in manifest['documents']}
    source_map = {}
    for filename in filenames:
        rows = [row for row in frozen['sources'] if row['original_filename'] == filename]
        source_map[filename] = select_active_source(rows, declared[filename])
        if not all(source_map[filename][field] for field in ('parser_pipeline_sha256', 'raw_members_fingerprint')):
            raise ValueError('Expected a published Raw snapshot: ' + filename)
    if args.condition == 'baseline' and (frozen['summaries'] or frozen['derived'] or frozen['graph']):
        raise ValueError('Raw-only baseline requires zero formal-source derived publication; no flag bypass')
    if args.condition == 'layered':
        for case in selected:
            version_ids = {source_map[name]['version_id'] for name in case['scopeFilenames']}
            if not any(asset['dependencies'] and set(asset['dependencies']) <= version_ids
                       for asset in frozen['derived']):
                raise ValueError('Layered case has no derived publication within its scope: ' + case['caseId'])
    report = dict(status='RUNNING_ACTUAL_FORMAL_QUERIES', startedAt=now(), condition=args.condition,
                  manifestSha256=hashlib.sha256(args.manifest.read_bytes()).hexdigest(), process=process,
                  runnerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), sourceSnapshot=frozen,
                  workerCount=args.workers,
                  forbiddenClaimPolicy=CLAIM_POLICY, factTextPolicy=FACT_TEXT_POLICY,
                  answerDeliveryPolicy=DELIVERY_POLICY,
                  scoringHelpers={name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                                  for name in ('formal_evidence_scoring.py', 'formal_claim_scoring.py',
                                               'formal_retrieval_budget.py', 'formal_source_identity.py', 'formal_text_normalization.py',
                                           'formal_answer_delivery.py', 'formal_campaign.py', 'formal_target.py')},
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

    prior_attempts = {}
    if args.resume_from:
        prior_bytes = args.resume_from.read_bytes()
        prior_attempts = resume_attempts(json.loads(prior_bytes), report)
        report['resumedFrom'] = dict(summary=str(args.resume_from.resolve()),
                                     sha256=hashlib.sha256(prior_bytes).hexdigest())
        report['attempts'] = list(prior_attempts.values())
    lock = threading.RLock()
    stop = threading.Event()

    def persist():
        with lock:
            counts = Counter(attempt.get('outcome', 'IN_FLIGHT') for attempt in report['attempts'])
            report['counts'] = dict(counts)
            report['unstartedAttempts'] = report['plannedAttempts'] - len(report['attempts'])
            temp = output / 'summary.json.tmp'
            temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            temp.replace(output / 'summary.json')

    def publish(attempt):
        with lock:
            key = (attempt['caseId'], attempt['repeat'])
            report['attempts'] = [row for row in report['attempts']
                                  if (row['caseId'], row['repeat']) != key] + [copy.deepcopy(attempt)]
            persist()

    persist()
    def execute(case, repeat, previous_attempt):
        if stop.is_set():
            return
        if target.process() != process:
            raise ValueError('Deployment changed; do not mix running artifacts into this frozen campaign')
        current = source_snapshot(output / ('sources-before-' + case['caseId'] + '-r' + str(repeat)))
        if current != frozen:
            raise ValueError('Source, ACL or derived publication changed; preserve partial evaluation')
        if runtime_snapshot(output / ('runtime-before-' + case['caseId'] + '-r' + str(repeat))) != frozen_runtime:
            raise ValueError('Active model profile, tokenizer or budgets changed; preserve the partial campaign')
        request = owned_client(case['account'])
        scoped_ids = [source_map[name]['version_id'] for name in case['scopeFilenames']]
        eligible = {(row['asset_id'], row['asset_version']) for row in frozen['derived']
                    if row['dependencies'] and set(row['dependencies']) <= set(scoped_ids)}
        attempt = copy.deepcopy(previous_attempt) if previous_attempt else dict(
            caseId=case['caseId'], repeat=repeat, split=case['split'],
            category=case['category'], startedAt=now(), observations=[])
        publish(attempt)
        elapsed = (datetime.now(timezone.utc) - datetime.fromisoformat(attempt['startedAt'])).total_seconds()
        started = time.monotonic() - elapsed
        if not attempt.get('sessionId'):
            code, result = request('/query', dict(question=case['question'], limit=8,
                filters=dict(version_ids=scoped_ids)))
            attempt['startHttpStatus'] = code
            if code != 200:
                attempt.update(outcome='FAIL_START', final=result, finishedAt=now(), elapsedSeconds=time.monotonic() - started)
                publish(attempt)
                return
            session = str(uuid.UUID(result['session_id']))
            attempt['sessionId'] = session
            publish(attempt)
        else:
            session = str(uuid.UUID(attempt['sessionId']))
        deadline, previous = time.monotonic() + max(0, args.observe_seconds - elapsed), None
        first_observation = True
        while first_observation or time.monotonic() < deadline:
            first_observation = False
            code, result = request('/query/session?id=' + session)
            state = result.get('status')
            if code != 200:
                raise ValueError('Owned normal session became unreadable')
            if state != previous:
                attempt['observations'].append(dict(at=now(), status=state))
                previous = state
                publish(attempt)
            if state in TERMINAL:
                break
            time.sleep(3)
        else:
            attempt.update(outcome='FAIL_OBSERVATION_TIMEOUT', final=result, finishedAt=now())
            publish(attempt)
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
 'assetExpansions',(SELECT coalesce(jsonb_agg(x ORDER BY first_seen_round, asset_id, evidence_id),'[]')
   FROM query_asset_expansion x WHERE session_id='{session}'),
 'sourceCredits',(SELECT count(*) FROM source_usage_contribution WHERE session_id='{session}'),
 'entryCredits',(SELECT count(*) FROM asset_usage_contribution WHERE session_id='{session}'));
""", output / (case['caseId'] + '-r' + str(repeat)))
        answer = canonical(result.get('answer', ''))
        citations = result.get('citations', [])
        checks = {
            'allCitationContextualHashesMatchHeadingAndBody': all(contextual_evidence(c['evidence']) is not None for c in citations),
            'ownedNativeSessionAndStatus': native['session']['user_id'] == accounts[case['account']]['user_id'] and native['session']['status'] == result['status'],
            'expectedTerminalStatus': result['status'] in case['expectedStatus'],
            'answerEqualsSavedNativeText': result.get('answer') == native['session']['final_answer'],
            'answeredStatusRequiresActuallyGroundedDelivery': terminal_delivery_valid(result),
            'budgetAtMostEightUniqueReservations': len(native['reservations']) <= 8 and len({r['round_no'] for r in native['reservations']}) == len(native['reservations']) and all(1 <= r['round_no'] <= 8 for r in native['reservations']),
            'actualEightQueryAndThreePerRoundBudget': within_query_budget(native['reservations']),
            'completedRoundsHaveActualReservedIdentity': all(r['round_id'] in {s['reservation_id'] for s in native['reservations']} for r in native['rounds']),
            'allCitationsBelongToNativeEvidenceAndDeclaredScope': all(c['evidence']['evidence_id'] in {e['evidence_id'] for e in native['evidence']} and c['evidence']['version_id'] in {source_map[name]['version_id'] for name in case['scopeFilenames']} for c in citations),
            'allModelInputsRespectCurrentAccountAuthority': all(e['security_level'] is not None and e['security_level'] <= (10 if case['account'] == 'public' else 30) for e in native['evidence'] + native['dependencies']),
            'allNativeInputsWithinDeclaredVersionScope': all(e['version_id'] in scoped_ids for e in native['evidence'] + native['dependencies']),
            'allEntryExpansionsWithinFrozenEligibleRevisions': all(
                (row['asset_id'], row['asset_version']) in eligible and row['source_version_id'] in scoped_ids
                for row in native['assetExpansions']),
            'noEvaluationManufacturesDownloadHeat': native['sourceCredits'] == native['entryCredits'] == 0,
            'noForbiddenRestrictedSources': all(c['evidence'].get('source_filename') not in case.get('forbiddenSourceFilenames', []) for c in citations),
            'noExplicitForbiddenClaims': not any(asserted(result.get('answer', ''), phrase) for phrase in case.get('forbiddenClaims', [])),
        }
        for index, fact in enumerate(case['requiredFacts'], 1):
            checks['fact-%02d-answer' % index] = contains_fact(result.get('answer', ''), fact['text'])
            if fact['text'] in case.get('derivedFacts', []):
                checks['fact-%02d-source-evidence' % index] = all(any(c['evidence'].get('source_filename') == name and c['evidence'].get('raw_content') for c in citations) for name in fact['sources'])
            else:
                checks['fact-%02d-source-evidence' % index] = any(c['evidence'].get('source_filename') in fact['sources'] and contains_fact(contextual_evidence(c['evidence']) or '', fact['text']) for c in citations)
        if case['category'] != 'safety':
            checks['actualGroundedModelAnswer'] = result['status'] == 'ANSWERED' and not result.get('evidence_only') and bool(citations)
        else:
            checks['missingOrConflictIsExplicit'] = bool(re.search(r'不足|不记录|未记录|未确认|无法|不能|冲突|争议|没有|未知|不一致|看不到|未提供|未找到|无权|权限', answer))
        attempt.update(outcome='PASS_STRICT_FACTS_AND_EVIDENCE' if all(checks.values()) else 'FAIL_STRICT_FACTS_OR_EVIDENCE',
                       final=result, native=native, checks=checks, evidenceScoringPolicy=POLICY, finishedAt=now(), elapsedSeconds=time.monotonic() - started,
                       actualConsumedRounds=len(native['reservations']), completedRounds=len(native['rounds']),
                       eligibleDerivedRevisions=len(eligible),
                       expandedDerivedRevisions=len({(row['asset_id'], row['asset_version']) for row in native['assetExpansions']}))
        publish(attempt)
        print(json.dumps(dict(caseId=case['caseId'], repeat=repeat, status=result['status'],
                              outcome=attempt['outcome'], rounds=attempt['actualConsumedRounds'],
                              failedChecks=[key for key, value in checks.items() if not value])), flush=True)

    futures = []
    with ThreadPoolExecutor(max_workers=args.workers) as workers:
        for repeat in range(1, args.repeats + 1):
            for case in selected:
                previous = prior_attempts.get((case['caseId'], repeat))
                if requires_execution(previous):
                    futures.append(workers.submit(execute, case, repeat, previous))
        try:
            for future in as_completed(futures):
                try:
                    future.result()
                except Exception as failure:
                    stop.set()
                    with lock:
                        report.setdefault('stopReasons', []).append(dict(type=type(failure).__name__, message=str(failure)))
                        persist()
        except KeyboardInterrupt:
            stop.set()
            with lock:
                report.setdefault('stopReasons', []).append(dict(type='ControllerInterrupted', message='No new admissions; current owned sessions drain normally'))
                persist()
            for pending in futures:
                pending.cancel()
    try:
        if (target.process() != process
                or source_snapshot(output / 'final-sources') != frozen
                or runtime_snapshot(output / 'final-runtime') != frozen_runtime):
            raise ValueError('Frozen inputs changed before the last query finished')
    except Exception as failure:
        report.setdefault('stopReasons', []).append(dict(type=type(failure).__name__, message=str(failure)))
    report['status'] = ('PARTIAL_ACTUAL_FORMAL_QUERIES' if report.get('stopReasons')
                        or report.get('unstartedAttempts') else 'COMPLETED_ACTUAL_FORMAL_QUERIES')
    report['finishedAt'] = now()
    persist()
    print(json.dumps(dict(status=report['status'], counts=report['counts'], unstartedAttempts=report['unstartedAttempts'])), flush=True)
    raise SystemExit(int(report['status'] != 'COMPLETED_ACTUAL_FORMAL_QUERIES' or any(not key.startswith('PASS') for key in report['counts'])))


if __name__ == '__main__':
    main()
