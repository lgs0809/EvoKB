-- Read-only manual inspection. Override with psql -v version_id=<source UUID>.
\if :{?version_id}
\else
\set version_id '2891417d-c046-4f73-81c2-09152bd95d5c'
\endif
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT task_id,version_id,task_type,subject_id,status,attempt,execution_epoch,
       started_at,finished_at,stage_deadline,error_code,error_message,output_hash
FROM compile_task WHERE version_id=:'version_id' ORDER BY created_at;
SELECT e.* FROM kb_knowledge_entry e
WHERE EXISTS(SELECT 1 FROM kb_entry_source s WHERE s.knowledge_base_id=e.knowledge_base_id
             AND s.entry_id=e.entry_id AND s.source_version_id=:'version_id') ORDER BY e.entry_id;
SELECT s.*,a.acl_version AS current_acl,a.security_level
FROM kb_entry_source s LEFT JOIN kb_source_authority a ON a.version_id=s.source_version_id
WHERE EXISTS(SELECT 1 FROM kb_entry_source chosen WHERE chosen.knowledge_base_id=s.knowledge_base_id
             AND chosen.entry_id=s.entry_id AND chosen.source_version_id=:'version_id')
ORDER BY s.entry_id,s.source_version_id,s.summary_id;
SELECT b.*,a.acl_version AS current_acl,s.lifecycle,s.grounding_status,s.coverage_status
FROM kb_entry_build_input b LEFT JOIN kb_source_authority a ON a.version_id=b.source_version_id
LEFT JOIN summary s ON s.summary_id=b.summary_id
WHERE EXISTS(SELECT 1 FROM kb_entry_source chosen WHERE chosen.knowledge_base_id=b.knowledge_base_id
             AND chosen.entry_id=b.entry_id AND chosen.source_version_id=:'version_id')
ORDER BY b.entry_id,b.entry_revision,b.summary_id;
SELECT c.*,a.stage,a.publication_state,a.projection_hash FROM kb_asset_current c
JOIN kb_derived_asset a USING(asset_id,asset_version)
WHERE EXISTS(SELECT 1 FROM kb_entry_source chosen WHERE chosen.entry_id=c.asset_id
             AND chosen.source_version_id=:'version_id') ORDER BY c.asset_id;
SELECT p.* FROM kb_entry_pending_source p
WHERE p.source_version_id=:'version_id' OR EXISTS(
  SELECT 1 FROM kb_entry_source chosen WHERE chosen.knowledge_base_id=p.knowledge_base_id
  AND chosen.entry_id=p.entry_id AND chosen.source_version_id=:'version_id') ORDER BY p.entry_id;
SELECT u.* FROM asset_usage_contribution u
WHERE EXISTS(SELECT 1 FROM kb_entry_source chosen WHERE chosen.entry_id=u.asset_id
             AND chosen.source_version_id=:'version_id') ORDER BY u.created_at;
SELECT s.* FROM kb_generation_daily_selection s JOIN compile_task t USING(task_id)
WHERE t.version_id=:'version_id' OR EXISTS(SELECT 1 FROM kb_entry_source chosen
  WHERE chosen.knowledge_base_id=s.knowledge_base_id AND chosen.entry_id=s.target_id
    AND chosen.source_version_id=:'version_id') ORDER BY s.business_day,s.stage;
COMMIT;
