-- Read-only. psql -v logical_name='raw-cache-acceptance-<fixture-id>' -f this-file.
-- Never write success states; use normal API/browser batch creation and execution.
SELECT d.document_id,d.logical_name,v.version_id,v.version_no,v.lifecycle,v.sha256,
       v.original_object_key,v.parser_name,v.parser_version,v.structured_object_key
FROM document d JOIN document_version v USING(document_id)
WHERE d.logical_name=:'logical_name' ORDER BY v.version_no;

SELECT f.file_id,f.batch_id,f.status,f.progress_stage,f.attempt,f.document_id,f.version_id,
       f.error_code,f.started_at,f.finished_at
FROM ingestion_file f WHERE f.logical_name=:'logical_name' ORDER BY f.created_at;

SELECT b.version_id,b.block_no,b.block_type,b.heading_path,b.content_hash,b.token_count,
       b.page_start,b.page_end,b.source_block_ids
FROM raw_block b JOIN document_version v USING(version_id) JOIN document d USING(document_id)
WHERE d.logical_name=:'logical_name' ORDER BY v.version_no,b.block_no;

SELECT e.version_id,e.evidence_id,e.block_id,e.active,e.raw_location
FROM evidence e JOIN document d USING(document_id)
WHERE d.logical_name=:'logical_name' ORDER BY e.version_id,e.evidence_id;

-- Cache intentionally has no plaintext or source ACL. Derive exact keys through the immutable
-- deployed RawEncodingReadbackProbe; global COUNT(*) cannot certify this document or a cache hit.
SELECT profile_hash,dimensions,count(*),min(created_at),max(created_at)
FROM raw_vector_cache GROUP BY profile_hash,dimensions;
