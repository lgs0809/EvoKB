-- Read-only inspection; pass the actual document ID with psql -v document_id=...
-- This does not retry tasks, authenticate a checkpoint or update any source state.
\if :{?document_id}
\else
  \echo 'Required: -v document_id=<actual-document-id>'
  \quit 2
\endif

SELECT jsonb_build_object(
  'observed_at', clock_timestamp(),
  'versions', (
    SELECT jsonb_agg(to_jsonb(v) ORDER BY v.version_no)
    FROM document_version v WHERE v.document_id = :'document_id'
  ),
  'metadata', (
    SELECT jsonb_agg(to_jsonb(m) ORDER BY m.version_id)
    FROM document_metadata m JOIN document_version v USING (version_id)
    WHERE v.document_id = :'document_id'
  ),
  'raw_blocks', (
    SELECT jsonb_agg(to_jsonb(b) ORDER BY b.block_no, b.block_id)
    FROM raw_block b JOIN document_version v USING (version_id)
    WHERE v.document_id = :'document_id'
  ),
  'files', (
    SELECT jsonb_agg(to_jsonb(f) ORDER BY f.created_at)
    FROM ingestion_file f WHERE f.document_id = :'document_id'
  ),
  'publications', (
    SELECT jsonb_agg(to_jsonb(p) ORDER BY p.version_id)
    FROM raw_publication p WHERE p.document_id = :'document_id'
  ),
  'publication_members', (
    SELECT jsonb_agg(to_jsonb(m) ORDER BY m.version_id, m.physical_id)
    FROM raw_publication_member m JOIN document_version v USING (version_id)
    WHERE v.document_id = :'document_id'
  ),
  'authority', (
    SELECT jsonb_agg(to_jsonb(a))
    FROM kb_source_authority a WHERE a.document_id = :'document_id'
  ),
  'deliveries', (
    SELECT jsonb_agg(to_jsonb(o) ORDER BY o.created_at)
    FROM raw_ingestion_outbox o JOIN ingestion_file f USING (file_id)
    WHERE f.document_id = :'document_id'
  )
);
