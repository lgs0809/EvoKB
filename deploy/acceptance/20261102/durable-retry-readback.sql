-- Read only. No attempt reset, task state mutation, usage or success is manufactured.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT task_id,version_id,task_type,status,attempt,execution_epoch,priority,next_attempt_at,started_at,finished_at,error_code
FROM compile_task WHERE task_id IN ('5c9895d2-c7a2-485a-9025-6343e5f967a4','bcc67152-5443-4d47-9af6-5cbd21a56b65','6639b354-aef6-49f6-933b-dfa1dc5275de','605015db-5ce9-4f06-9d99-556518e794b0')
OR (task_type='CONCEPT' AND version_id='75782b74-0580-49af-8ea5-a4e3bad22102') ORDER BY created_at;
SELECT * FROM compile_task_failure WHERE task_id='5c9895d2-c7a2-485a-9025-6343e5f967a4' ORDER BY attempt;
SELECT summary_id,lifecycle,object_key,content_hash,created_at FROM summary
WHERE version_id IN ('75782b74-0580-49af-8ea5-a4e3bad22102','32a185cf-c840-4be7-9df9-9b933c1470fb') ORDER BY created_at;
SELECT e.entry_id,e.revision,e.preferred_label,e.lifecycle,s.source_version_id,s.summary_id,s.fact_ids AS description_fact_ids,s.scope_fact_ids
FROM kb_knowledge_entry e JOIN kb_entry_source s USING(knowledge_base_id,entry_id)
WHERE s.source_version_id IN ('75782b74-0580-49af-8ea5-a4e3bad22102','32a185cf-c840-4be7-9df9-9b933c1470fb') ORDER BY e.entry_id,s.summary_id;
SELECT f.file_id,f.status,f.attempt,f.error_code,f.next_attempt_at,f.version_id,f.claimed_parser_pipeline_sha256,
 v.parser_pipeline_sha256,v.parse_checkpoint_json
FROM ingestion_file f LEFT JOIN document_version v ON v.version_id=f.version_id
WHERE f.file_id IN ('047424ea-09ff-4bd3-aae7-380e2989504d','af87c189-f720-47dc-a6e1-867bcd48da4e');
COMMIT;
