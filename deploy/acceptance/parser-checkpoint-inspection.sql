-- Read-only inspection. No source body, object credential or model secret is selected.
BEGIN READ ONLY;
SELECT v.version_id,v.document_id,v.version_no,v.lifecycle,v.sha256,v.size_bytes,
       v.parser_name,v.parser_version,v.parser_pipeline_sha256,
       (v.parse_checkpoint_json IS NOT NULL) AS has_checkpoint,
       v.parse_checkpoint_json->>'sourceSha256' AS checkpoint_source_sha256,
       v.parse_checkpoint_json->>'sourceSizeBytes' AS checkpoint_bytes,
       v.parse_checkpoint_json->>'rawBlocksSha256' AS checkpoint_blocks_sha256,
       v.reparse_from_version_id,v.duplicate_of_version_id,
       (SELECT count(*) FROM raw_block b WHERE b.version_id=v.version_id) AS raw_block_count,
       (SELECT count(*) FROM raw_publication_member m WHERE m.version_id=v.version_id) AS published_members
FROM document_version v ORDER BY v.document_id,v.version_no;
SELECT f.file_id,f.batch_id,f.version_id,f.status,f.attempt,f.execution_epoch,
       f.execution_stage,f.stage_deadline,f.next_attempt_at,f.claimed_parser_pipeline_sha256,
       f.error_code,f.isolated,o.delivery_epoch,o.message_id,o.dispatched_at
FROM ingestion_file f LEFT JOIN raw_ingestion_outbox o USING(file_id) ORDER BY f.created_at;
SELECT a.version_id,a.document_id,a.security_level,a.acl_version,
       p.producer_scope,p.execution_epoch,p.published_at
FROM kb_source_authority a LEFT JOIN raw_publication p USING(version_id)
ORDER BY a.document_id,a.version_id;
ROLLBACK;
