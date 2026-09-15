-- Read-only two-source legacy reparse inspection. No old identity/attempt/state updates.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT f.file_id,f.status,f.attempt,f.error_code,f.claimed_parser_pipeline_sha256,
 v.version_id,v.version_no,v.lifecycle,v.sha256,v.size_bytes,v.original_object_key,
 v.parser_pipeline_sha256,v.parse_checkpoint_json,
 (SELECT count(*) FROM raw_block b WHERE b.version_id=v.version_id) retained_raw_blocks
FROM ingestion_file f JOIN document_version v ON v.version_id=f.version_id
WHERE f.file_id IN ('047424ea-09ff-4bd3-aae7-380e2989504d','af87c189-f720-47dc-a6e1-867bcd48da4e');
SELECT v.document_id,v.version_id,v.version_no,v.lifecycle,v.reparse_from_version_id,
 v.sha256,v.size_bytes,v.original_object_key,v.parser_name,v.parser_version,v.parser_pipeline_sha256,
 f.file_id,f.status,f.attempt,f.execution_epoch,f.progress_stage,f.next_attempt_at,f.error_code,
 o.message_id,o.delivery_epoch,r.producer_scope,r.execution_epoch AS publication_epoch,
 (SELECT count(*) FROM raw_publication_member m WHERE m.version_id=v.version_id) physical_members
FROM document_version v JOIN ingestion_file f ON f.version_id=v.version_id
LEFT JOIN raw_ingestion_outbox o ON o.file_id=f.file_id
LEFT JOIN raw_publication r ON r.version_id=v.version_id
WHERE v.reparse_from_version_id IN ('607b1e4f-035e-427d-8156-916133dc8128','6125901a-0caf-4dac-ac82-770897326be8')
ORDER BY v.document_id,v.version_no;
SELECT file_id,attempt,error_code,occurred_at,next_attempt_at FROM raw_ingestion_failure
WHERE file_id IN ('047424ea-09ff-4bd3-aae7-380e2989504d','af87c189-f720-47dc-a6e1-867bcd48da4e') ORDER BY file_id,attempt;
COMMIT;
