-- Local administrator inspection only. No mutations or raw evidence/quoted audit output.
-- psql -v version_id='actual-source-version' -f grounding-audit-inspection.sql
BEGIN READ ONLY;
SELECT s.summary_id,s.version_id,s.lifecycle,s.grounding_status,s.coverage_status,
       count(f.fact_id) AS facts,
       count(f.fact_id) FILTER (WHERE f.grounding_audit IS NULL) AS historical_without_audit,
       count(f.fact_id) FILTER (WHERE f.grounding_audit->>'policy_version'='grounding-constraints-v1'
         AND jsonb_array_length(f.grounding_audit->'checks')=7) AS current_audit_records
FROM summary s LEFT JOIN summary_fact f USING(summary_id)
WHERE s.version_id=:'version_id'
GROUP BY s.summary_id,s.version_id,s.lifecycle,s.grounding_status,s.coverage_status
ORDER BY s.summary_id;

SELECT task_id,task_type,status,attempt,error_code,started_at,finished_at,created_at,
       execution_epoch,stage_deadline
FROM compile_task WHERE version_id=:'version_id' ORDER BY created_at;
SELECT task_id,attempt,outcome,error_code,occurred_at,next_attempt_at
FROM compile_task_failure WHERE version_id=:'version_id' ORDER BY occurred_at;
SELECT asset_type,asset_id,build_revision,source_version_id,acl_version
FROM kb_asset_build_dependency WHERE source_version_id=:'version_id'
ORDER BY asset_type,asset_id,build_revision;
SELECT asset_id,asset_version,source_version_id,acl_version
FROM kb_asset_dependency WHERE source_version_id=:'version_id'
ORDER BY asset_id,asset_version;
COMMIT;
