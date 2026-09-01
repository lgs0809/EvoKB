-- Build inputs are separate from final citations and from the growing asset source catalog.
-- The reader must check every input of a published immutable revision, including inputs
-- whose facts were rejected by a model or omitted from the final prose.
CREATE TABLE kb_asset_build_dependency (
  asset_type VARCHAR(32) NOT NULL CHECK(asset_type IN ('SUMMARY','CONCEPT','WIKI')),
  asset_id VARCHAR(255) NOT NULL,
  build_revision VARCHAR(64) NOT NULL,
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  acl_version BIGINT NOT NULL CHECK(acl_version>0),
  PRIMARY KEY(asset_type,asset_id,build_revision,source_version_id)
);
CREATE INDEX kb_build_dependency_source ON kb_asset_build_dependency(source_version_id,asset_type,asset_id);
