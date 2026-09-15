"""Read-only finance authority, full derived input and existing query-ledger audit."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

R = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh output required; historical audit must be retained')
a.output.parent.mkdir(parents=True, exist_ok=True)
state = json.loads((R / '.tmp/target/process.json').read_text())
manifest = R / 'deploy/acceptance/formal-evaluation/manifest.json'
gold = json.loads(manifest.read_text())
finances = [x for x in gold['documents'] if x['kind'] == 'finance' and x['family'] in ['EKB-F01', 'EKB-F02', 'EKB-F03']]
if len(finances) != 3:
    raise SystemExit('Expected frozen first three finance documents')
values = ','.join("('%s',%d)" % (x['logicalName'].replace("'", "''"), x['securityLevel']) for x in finances)
sql = """
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='30s';
SET LOCAL lock_timeout='1s';
WITH gold(logical_name,gold_security) AS (VALUES __VALUES__),
v AS (SELECT g.*,d.document_id,d.status AS document_status,dv.version_id,dv.lifecycle,
 dv.version_no,dv.sha256,dv.original_filename,m.security_level,m.acl_version
 FROM gold g LEFT JOIN document d ON d.logical_name=g.logical_name
 LEFT JOIN document_version dv USING(document_id)
 LEFT JOIN document_metadata m USING(version_id)),
qids AS (
 SELECT session_id FROM query_source_dependency WHERE version_id IN (SELECT version_id FROM v)
 UNION SELECT q.session_id FROM query_evidence q JOIN evidence e USING(evidence_id) WHERE e.version_id IN (SELECT version_id FROM v)
 UNION SELECT session_id FROM query_asset_expansion WHERE source_version_id IN (SELECT version_id FROM v)
 UNION SELECT session_id FROM package_answer_source WHERE version_id IN (SELECT version_id FROM v)
 UNION SELECT q.session_id FROM query_session q JOIN evidence e ON q.citation_evidence_ids ? e.evidence_id WHERE e.version_id IN (SELECT version_id FROM v)
), ledger AS (
 SELECT q.session_id,q.user_id,u.username,u.role,u.status AS user_status,
 u.security_clearance AS current_user_clearance,q.status,q.created_at,q.updated_at,q.deleted_at,
 md5(q.original_question) AS question_md5,length(q.original_question) AS question_chars,
 length(q.final_answer) AS answer_chars,q.filters_json,q.citation_evidence_ids
 FROM qids JOIN query_session q USING(session_id) JOIN enterprise_user u USING(user_id)
)
SELECT jsonb_build_object(
 'databaseClock',clock_timestamp(),'databaseRecovery',pg_is_in_recovery(),
 'transactionReadOnly',current_setting('transaction_read_only'),
 'sources',COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY logical_name,version_no) FROM v t),'[]'),
 'currentAuthority',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_source_authority t WHERE version_id IN(SELECT version_id FROM v)),'[]'),
 'rawPublication',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM raw_publication t WHERE version_id IN(SELECT version_id FROM v)),'[]'),
 'ingestionTasks',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM (SELECT file_id,batch_id,document_id,version_id,status,progress_stage,attempt,execution_epoch,error_code,started_at,finished_at,isolated FROM ingestion_file WHERE version_id IN(SELECT version_id FROM v))t),'[]'),
 'compileTasksByFullInputs',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM (SELECT DISTINCT c.task_id,c.version_id,c.task_type,c.status,c.attempt,c.execution_epoch,c.source_acl_version,c.created_at,c.started_at,c.finished_at,c.error_code FROM compile_task c WHERE c.version_id IN(SELECT version_id FROM v) OR c.task_id IN(SELECT task_id FROM compile_task_input WHERE source_version_id IN(SELECT version_id FROM v)))t),'[]'),
 'allInputRowsForAffectedCompileTasks',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM compile_task_input t WHERE task_id IN(SELECT task_id FROM compile_task WHERE version_id IN(SELECT version_id FROM v)) OR task_id IN(SELECT task_id FROM compile_task_input WHERE source_version_id IN(SELECT version_id FROM v))),'[]'),
 'summaryRows',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM summary t WHERE version_id IN(SELECT version_id FROM v)),'[]'),
 'conceptRows',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM concept_candidate t WHERE summary_id IN(SELECT summary_id FROM summary WHERE version_id IN(SELECT version_id FROM v))),'[]'),
 'affectedDerivedAssetsAllInputs',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_asset_dependency t WHERE (asset_id,asset_version) IN(SELECT asset_id,asset_version FROM kb_asset_dependency WHERE source_version_id IN(SELECT version_id FROM v))),'[]'),
 'affectedDerivedAssetsAllMembers',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_asset_source t WHERE (asset_id,asset_version) IN(SELECT asset_id,asset_version FROM kb_asset_dependency WHERE source_version_id IN(SELECT version_id FROM v)) OR (asset_id,asset_version) IN(SELECT asset_id,asset_version FROM kb_asset_source WHERE source_version_id IN(SELECT version_id FROM v))),'[]'),
 'affectedLegacyBuildsAllInputs',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_asset_build_dependency t WHERE (asset_type,asset_id,build_revision) IN(SELECT asset_type,asset_id,build_revision FROM kb_asset_build_dependency WHERE source_version_id IN(SELECT version_id FROM v))),'[]'),
 'affectedEntriesAllBuildInputs',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_entry_build_input t WHERE (knowledge_base_id,entry_id) IN(SELECT knowledge_base_id,entry_id FROM kb_entry_build_input WHERE source_version_id IN(SELECT version_id FROM v)) OR (knowledge_base_id,entry_id) IN(SELECT knowledge_base_id,entry_id FROM kb_entry_source WHERE source_version_id IN(SELECT version_id FROM v))),'[]'),
 'affectedEntriesAllMembers',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_entry_source t WHERE (knowledge_base_id,entry_id) IN(SELECT knowledge_base_id,entry_id FROM kb_entry_build_input WHERE source_version_id IN(SELECT version_id FROM v)) OR (knowledge_base_id,entry_id) IN(SELECT knowledge_base_id,entry_id FROM kb_entry_source WHERE source_version_id IN(SELECT version_id FROM v))),'[]'),
 'relatedPendingEntrySources',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_entry_pending_source t WHERE source_version_id IN(SELECT version_id FROM v) OR EXISTS(SELECT 1 FROM v WHERE t.affected_source_versions ? v.version_id)),'[]'),
 'relatedGraphEvidence',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM kb_graph_evidence t JOIN evidence e USING(evidence_id) WHERE e.version_id IN(SELECT version_id FROM v)),'[]'),
 'relatedWikiEvidence',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM wiki_evidence t JOIN evidence e USING(evidence_id) WHERE e.version_id IN(SELECT version_id FROM v)),'[]'),
 'existingRelatedQueries',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM ledger t),'[]'),
 'existingRelatedQueriesWithCurrentClearanceBelowGold',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM ledger t WHERE current_user_clearance<30),'[]'),
 'relatedQueryFullSourceDependencies',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM query_source_dependency t WHERE session_id IN(SELECT session_id FROM qids)),'[]'),
 'relatedQueryEvidence',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM query_evidence t JOIN evidence e USING(evidence_id) WHERE e.version_id IN(SELECT version_id FROM v)),'[]'),
 'relatedQueryActualAssetExpansion',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM query_asset_expansion t WHERE source_version_id IN(SELECT version_id FROM v)),'[]'),
 'relatedPackages',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM package_manifest_source t WHERE version_id IN(SELECT version_id FROM v)),'[]'),
 'relatedPackageCompletedReceipts',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM package_download_receipt t WHERE package_id IN(SELECT package_id FROM package_manifest_source WHERE version_id IN(SELECT version_id FROM v))),'[]'),
 'relatedLegacySearchHits',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM effective_version_hit t WHERE version_id IN(SELECT version_id FROM v)),'[]'),
 'relatedMetadataAudit',COALESCE((SELECT jsonb_agg(to_jsonb(t)) FROM (SELECT event_id,actor_user_id,actor_username,actor_role,action,target_type,target_id,outcome,created_at FROM audit_event WHERE target_id IN(SELECT version_id FROM v) OR target_id IN(SELECT document_id FROM v))t),'[]')
);
COMMIT;
""".replace('__VALUES__', values)
a.output.with_suffix('.sql').write_text(sql)
result = subprocess.run(['docker','exec','-i','evokb-target-postgres-1','psql','-X','-v','ON_ERROR_STOP=1','-qAt','-U','evokb_target','-d','evokb_target'],input=sql,capture_output=True,text=True,timeout=120)
a.output.with_suffix('.psql.log').write_text(result.stderr)
report = {'status':'FAIL_READONLY_FINANCE_LINEAGE_AUDIT','sqlMutations':0,'modelRequests':0,'actualAuditTime':datetime.now(timezone.utc).isoformat(),'readerRuntime':state,'formalManifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest(),'sqlSha256':hashlib.sha256(sql.encode()).hexdigest(),'processExitCode':result.returncode}
if result.returncode == 0:
    report['readback'] = json.loads(result.stdout)
    report['status'] = 'READONLY_FINANCE_LINEAGE_AUDIT'
    report['observedRelatedQueryCount'] = len(report['readback']['existingRelatedQueries'])
    report['observedRelatedQueryCurrentLowClearanceCount'] = len(report['readback']['existingRelatedQueriesWithCurrentClearanceBelowGold'])
report['runtimeUnchangedDuringAudit'] = json.loads((R / '.tmp/target/process.json').read_text()) == state
report['boundary'] = 'Read-only repeatable-read snapshot. This audits stored full-input/source/evidence/package ledgers, not a new query or historical-role proof. Current user clearance cannot alone establish past clearance. No evidence in these ledgers does not prove no direct browsing or unrecorded exposure. Password/session tokens and question/answer prose are not exported.'
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:report.get(k) for k in ['status','observedRelatedQueryCount','observedRelatedQueryCurrentLowClearanceCount','runtimeUnchangedDuringAudit','processExitCode']},ensure_ascii=False))
raise SystemExit(0 if result.returncode == 0 else 1)
