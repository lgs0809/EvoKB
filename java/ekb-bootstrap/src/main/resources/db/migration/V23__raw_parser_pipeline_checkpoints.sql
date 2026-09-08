-- Old rows are retained as unknown; only a normal new parse can write a complete checkpoint.
ALTER TABLE document_version ADD COLUMN parser_pipeline_sha256 VARCHAR(64);
ALTER TABLE document_version ADD COLUMN parse_checkpoint_json JSONB;
ALTER TABLE document_version ADD CONSTRAINT raw_pipeline_sha256_shape
 CHECK(parser_pipeline_sha256 IS NULL OR parser_pipeline_sha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE document_version ADD CONSTRAINT raw_parse_checkpoint_shape
 CHECK(parse_checkpoint_json IS NULL OR
  (parser_pipeline_sha256 IS NOT NULL AND jsonb_typeof(parse_checkpoint_json)='object'));

-- An original may have different real processing versions. NULL remains one legacy source,
-- never a generated sentinel identity, and version_no remains independently unique.
DROP INDEX uq_document_sha;
CREATE UNIQUE INDEX uq_document_sha_legacy ON document_version(document_id,sha256)
 WHERE parser_pipeline_sha256 IS NULL AND sha256<>'UNVERIFIED';
CREATE UNIQUE INDEX uq_document_source_pipeline ON document_version(document_id,sha256,parser_pipeline_sha256)
 WHERE parser_pipeline_sha256 IS NOT NULL;

-- Pending transport reservations retain their own UNVERIFIED identity. Duplicate normal uploads
-- preserve that original object/session/version and point to the real qualified processing version.
ALTER TABLE document_version ADD COLUMN duplicate_of_version_id VARCHAR(36)
 REFERENCES document_version(version_id);
ALTER TABLE document_version ADD COLUMN reparse_from_version_id VARCHAR(36)
 REFERENCES document_version(version_id);
ALTER TABLE document_version ADD CONSTRAINT raw_processing_lineage_self
 CHECK((duplicate_of_version_id IS NULL OR duplicate_of_version_id<>version_id)
   AND (reparse_from_version_id IS NULL OR reparse_from_version_id<>version_id));

ALTER TABLE ingestion_file ADD COLUMN claimed_parser_pipeline_sha256 VARCHAR(64);
ALTER TABLE ingestion_file ADD CONSTRAINT raw_claimed_pipeline_shape
 CHECK(claimed_parser_pipeline_sha256 IS NULL OR claimed_parser_pipeline_sha256 ~ '^[0-9a-f]{64}$');
