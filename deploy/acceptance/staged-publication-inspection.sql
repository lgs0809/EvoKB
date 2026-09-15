-- Read-only. Optional: psql -v version_id=<actual version> -f this file.
-- Default is the fictional linked supplemental agreement, created through normal ingestion.
\if :{?version_id}
\else
\set version_id 'c7a8fd5f-39f0-45c0-8708-04abce30419d'
\endif
SELECT task_id,task_type,subject_id,status,attempt,owner_id,execution_epoch,
  started_at,stage_deadline,finished_at,error_code,error_message
FROM compile_task WHERE version_id=:'version_id' ORDER BY created_at,task_id;
SELECT f.* FROM compile_task_failure f JOIN compile_task t USING(task_id)
WHERE t.version_id=:'version_id' ORDER BY f.task_id,f.attempt;
SELECT i.*,a.acl_version AS current_acl,a.security_level,
  (a.version_id IS NOT NULL) AS source_currently_eligible
FROM compile_task_input i JOIN compile_task t USING(task_id)
LEFT JOIN kb_source_authority a ON a.version_id=i.source_version_id
WHERE t.version_id=:'version_id' ORDER BY i.task_id,i.execution_epoch,i.source_version_id;
SELECT a.asset_id,a.asset_version,a.stage,a.publication_state,a.projection_hash,
  c.asset_version AS current_version,d.source_version_id,d.acl_version,
  authority.acl_version AS current_acl,
  EXISTS(SELECT 1 FROM kb_asset_source s WHERE s.asset_id=a.asset_id
    AND s.asset_version=a.asset_version AND s.source_version_id=d.source_version_id) AS cited_in_final_entry
FROM kb_derived_asset a JOIN kb_asset_dependency d USING(asset_id,asset_version)
LEFT JOIN kb_asset_current c USING(asset_id)
LEFT JOIN kb_source_authority authority ON authority.version_id=d.source_version_id
WHERE EXISTS(SELECT 1 FROM kb_asset_dependency own WHERE own.asset_id=a.asset_id
  AND own.asset_version=a.asset_version AND own.source_version_id=:'version_id')
ORDER BY a.asset_id,a.asset_version,d.source_version_id;
