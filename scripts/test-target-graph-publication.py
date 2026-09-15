#!/usr/bin/env python3
"""Inspect a normally executed Graph task and the actual published graph, without data writes."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.parse
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def native_readback(artifact, kb_id, user_id, query, output):
    """Use the immutable deployed reader and its authority rules, without writes."""
    source = ROOT / 'scripts/PublishedGraphReadbackProbe.java'
    source_bytes = source.read_bytes()
    jar_bytes = artifact.read_bytes()
    settings = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                    if line and not line.startswith('#'))
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17']).decode().strip()) / 'bin'
    native_output = output.with_suffix('.native.json')
    with tempfile.TemporaryDirectory(prefix='evokb-graph-readback-') as temp:
        work = Path(temp)
        frozen_source = work / source.name
        frozen_source.write_bytes(source_bytes)
        output.with_suffix('.native.java').write_bytes(source_bytes)
        frozen_jar = work / 'deployed.jar'
        frozen_jar.write_bytes(jar_bytes)
        with zipfile.ZipFile(frozen_jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work / Path(name).name).write_bytes(archive.read(name))
        cp = str(work / '*') + os.pathsep + str(work)
        compiled = subprocess.run([str(java / 'javac'), '-cp', cp, '-d', str(work), str(frozen_source)],
                                  capture_output=True, text=True, timeout=60)
        output.with_suffix('.native.compile.log').write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        env = dict(os.environ, PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],
                   PROBE_JDBC_URL=settings['EKB_JDBC_URL'])
        result = subprocess.run([str(java / 'java'), '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', cp,
                                 '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
                                 'PublishedGraphReadbackProbe', str(native_output.resolve()), kb_id, user_id, query],
                                env=env, capture_output=True, timeout=90)
        output.with_suffix('.native.runtime.log').write_bytes(result.stdout + result.stderr)
        result.check_returncode()
    return json.loads(native_output.read_text()), hashlib.sha256(source_bytes).hexdigest()


def normalized_rows(rows):
    normalized = {}
    for row in rows:
        value = dict(row)
        value['evidence_ids'] = sorted(value['evidence_ids'])
        for field in ['valid_from', 'valid_to']:
            if value.get(field):
                value[field] = datetime.fromisoformat(value[field].replace('Z', '+00:00')).isoformat()
        if value['relation_id'] in normalized:
            raise ValueError('Duplicate graph relation identity')
        normalized[value['relation_id']] = value
    return normalized


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--task', required=True)
    parser.add_argument('--query', required=True)
    parser.add_argument('--execution-proof', type=Path, help='Preserved successful task proof when inspecting a later deployment; never claims a new model execution')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    uuid.UUID(args.task)
    if args.output.exists():
        raise ValueError('Keep all attempts; choose a fresh output')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    artifact = Path(state['artifact'])
    if hashlib.sha256(artifact.read_bytes()).hexdigest() != state['jarSha256']:
        raise ValueError('Deployed immutable artifact changed')
    sql = f"""
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH chosen AS (SELECT * FROM compile_task WHERE task_id='{args.task}'),
revisions AS (
  SELECT DISTINCT r.* FROM kb_graph_relation r JOIN kb_graph_evidence p USING(kb_id,relation_id,revision_id)
  JOIN evidence e USING(evidence_id) JOIN chosen t ON t.version_id=e.version_id WHERE r.is_active
), proofs AS (
  SELECT p.*, e.version_id, e.document_id, v.lifecycle, d.status AS document_status,
    m.acl_version AS current_acl, m.security_level, a.acl_version AS authority_acl,
    a.version_id AS authority_version_id, e.active AS evidence_active, b.content_hash AS raw_content_hash
  FROM revisions r JOIN kb_graph_evidence p USING(kb_id,relation_id,revision_id)
  JOIN evidence e USING(evidence_id) JOIN document_version v ON v.version_id=e.version_id
  JOIN document d ON d.document_id=v.document_id JOIN document_metadata m ON m.version_id=v.version_id
  JOIN raw_block b ON b.block_id=e.block_id LEFT JOIN kb_source_authority a ON a.version_id=v.version_id
)
SELECT jsonb_build_object(
 'task',(SELECT to_jsonb(t) FROM chosen t),
 'summary',(SELECT jsonb_build_object('summary_id',s.summary_id,'lifecycle',s.lifecycle,
   'grounding_status',s.grounding_status,'coverage_status',s.coverage_status)
   FROM summary s JOIN chosen t ON t.subject_id=s.summary_id),
 'document',(SELECT to_jsonb(v) - 'original_object_key' FROM document_version v JOIN chosen t USING(version_id)),
 'relations',(SELECT coalesce(jsonb_agg(to_jsonb(r)),'[]'::jsonb) FROM revisions r),
 'entities',(SELECT coalesce(jsonb_agg(to_jsonb(e)),'[]'::jsonb) FROM kb_graph_entity e
   WHERE EXISTS(SELECT 1 FROM revisions r WHERE r.kb_id=e.kb_id AND e.entity_id IN (r.source_entity_id,r.target_entity_id))),
 'proofs',(SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]'::jsonb) FROM proofs p),
 'failedLegacyOnlyRelations',(SELECT coalesce(jsonb_agg(r.relation_id),'[]'::jsonb)
   FROM relation_fact r JOIN relation_evidence_support p USING(relation_id)
   JOIN evidence e USING(evidence_id) JOIN chosen t ON t.version_id=e.version_id
   WHERE NOT EXISTS(SELECT 1 FROM revisions published WHERE published.relation_id=r.relation_id))
);
COMMIT;
"""
    args.output.with_suffix('.sql').write_text('-- Read-only inspection of a real UI-generated task.\n' + sql)
    result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAtq',
                             '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, text=True, capture_output=True, timeout=30)
    args.output.with_suffix('.sql.log').write_text(result.stdout + result.stderr)
    result.check_returncode()
    observed = json.loads(result.stdout)
    executed_current = datetime.fromisoformat(observed['task']['started_at']) > datetime.fromisoformat(state['startedAt'])
    execution_proof = None
    preserved_execution = False
    if args.execution_proof:
        execution_proof = json.loads(args.execution_proof.read_text())
        prior_task = execution_proof.get('observed', {}).get('task', {})
        preserved_execution = execution_proof.get('status') == 'PASS_ACTUAL_GRAPH_PUBLICATION' and all(
            observed['task'][key] == prior_task.get(key)
            for key in ['task_id', 'version_id', 'subject_id', 'attempt', 'started_at', 'finished_at', 'output_hash', 'status'])
    spec = importlib.util.spec_from_file_location('graph_permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    account = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())['admin']
    request, opener = module.client(account)
    status, graph = request('/graph?' + urllib.parse.urlencode(dict(q=args.query, depth=2, limit=100)))
    status_doc, detail = request('/document/detail?' + urllib.parse.urlencode(dict(version_id=observed['task']['version_id'])))
    relations = observed['relations']
    entities = observed['entities']
    proofs = observed['proofs']
    published_ids = {r['relation_id'] for r in relations}
    api_rows = graph.get('relations', [])
    api_ids = {r['relation_id'] for r in api_rows}
    business = [r for r in relations if r['relation_type'] != 'DOCUMENTS']
    kb_ids = {r['kb_id'] for r in relations}
    if len(kb_ids) != 1:
        raise ValueError('Task graph must belong to one trusted knowledge base')
    native, native_sha = native_readback(artifact, next(iter(kb_ids)), account['user_id'], args.query, args.output)
    chosen_proofs = [p for p in proofs if p['version_id'] == observed['task']['version_id']]
    if not chosen_proofs:
        raise ValueError('No actual task source evidence was published')
    evidence_id = chosen_proofs[0]['evidence_id']
    status_evidence, evidence = request('/evidence?' + urllib.parse.urlencode(dict(id=evidence_id)))
    source_url = module.BASE + '/api/enterprise/evidence/source/file?' + urllib.parse.urlencode(dict(id=evidence_id))
    with opener.open(source_url, timeout=30) as response:
        original = response.read()
        original_status = response.status
    checks = {
        'normalTaskSucceeded': observed['task']['status'] == 'SUCCEEDED' and bool(observed['task']['output_hash']),
        'actualTaskExecutionIsBoundToCurrentOrPreservedProof': executed_current or preserved_execution,
        'groundedCoveredSummaryRemainsActive': observed['summary'] == dict(summary_id=observed['task']['subject_id'], lifecycle='ACTIVE', grounding_status='PASS', coverage_status='PASS'),
        'businessGraphActuallyPublished': bool(business) and all(r['is_active'] and r['scope_key'] and r['revision_id'] for r in relations),
        'trustedDocumentNodeKeepsActualDocumentIdentity': any(e['entity_type'] == 'DOCUMENT' and e['object_id'] == observed['document']['document_id'] for e in entities),
        'noDerivedEntryNodeInvented': all(e['entity_type'] not in {'WIKI', 'CONCEPT'} for e in entities),
        'businessEndpointsGroundedAndScoped': all(e['scope_key'] and e['object_id'] and e['attributes'].get('identity_status') == 'SCOPE_GROUNDED' for e in entities if e['entity_type'] != 'DOCUMENT'),
        'everyPublishedRevisionHasRawSourceProof': all(any(p['relation_id'] == r['relation_id'] and p['revision_id'] == r['revision_id'] for p in proofs) for r in relations),
        'allProofsMatchCurrentSourceAuthority': bool(proofs) and all(p['lifecycle'] == p['document_status'] == 'ACTIVE' and p['evidence_active'] and p['authority_version_id'] == p['version_id'] and p['acl_version'] == p['current_acl'] == p['authority_acl'] for p in proofs),
        'normalGraphApiReturnsActuallyPublishedRelations': status == 200 and bool(api_rows) and normalized_rows(api_rows) == normalized_rows(native['relations']) and bool(api_ids & {r['relation_id'] for r in business}),
        'legacyFailedRelationsCannotLeakThroughGraphApi': api_ids.isdisjoint(observed['failedLegacyOnlyRelations']),
        'normalDocumentDetailReadable': status_doc == 200 and bool(detail),
        'documentStatusCountsActualPublication': status_doc == 200 and detail.get('knowledge', {}).get('active_relations') == len(relations) and detail.get('knowledge', {}).get('relation_evidence_supports') == len(proofs),
        'normalEvidencePreviewKeepsActualSourceFilename': status_evidence == 200 and evidence.get('source_filename') == observed['document']['original_filename'],
        'normalSourceFileBytesMatchStoredOriginalHash': original_status == 200 and hashlib.sha256(original).hexdigest() == observed['document']['sha256'],
    }
    report = dict(status='PASS_ACTUAL_GRAPH_PUBLICATION' if all(checks.values()) else 'FAIL_ACTUAL_GRAPH_PUBLICATION',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(), jarSha256=state['jarSha256'],
                  checks=checks, observed=observed, graphApi=graph, documentDetail=detail,
                  nativeGraphReadback=native, nativeReadbackSourceSha256=native_sha,
                  evidencePreview=evidence, originalSha256=hashlib.sha256(original).hexdigest(),
                  execution=dict(executedDuringCurrentDeployment=executed_current,
                                 executionJarSha256=state['jarSha256'] if executed_current else (execution_proof or {}).get('jarSha256'),
                                 preservedProofSha256=hashlib.sha256(args.execution_proof.read_bytes()).hexdigest() if args.execution_proof else None),
                  probeSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  boundary='Read-only task/source/graph inspection and normal account APIs after CUA retry. API relations must exactly match the deployed native current-authority reader, including other valid source relations reached by two-hop browsing, and include business relations from this actual task. A preserved execution proof certifies the earlier model execution only, never a new execution on a later artifact. No task/publication state written. Does not certify all Graph compilation scenarios or knowledge-base lifecycle requirements.')
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()), failed=[k for k,v in checks.items() if not v]), ensure_ascii=False))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
