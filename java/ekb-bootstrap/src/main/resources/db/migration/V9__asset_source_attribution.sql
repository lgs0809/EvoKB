-- Immutable entry revisions and their catalogue are not download facts or graph edges.
CREATE TABLE kb_derived_asset (
  asset_id VARCHAR(255) NOT NULL,
  asset_version BIGINT NOT NULL CHECK(asset_version>0),
  stage VARCHAR(16) NOT NULL CHECK(stage IN ('CONCEPT','WIKI')),
  projection_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(asset_id,asset_version),
  UNIQUE(asset_id,projection_hash)
);
-- All actual inputs, including sources omitted from the entry's final citations.
CREATE TABLE kb_asset_dependency (
  asset_id VARCHAR(255) NOT NULL,
  asset_version BIGINT NOT NULL,
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  acl_version BIGINT NOT NULL CHECK(acl_version>0),
  PRIMARY KEY(asset_id,asset_version,source_version_id),
  FOREIGN KEY(asset_id,asset_version) REFERENCES kb_derived_asset(asset_id,asset_version)
);
-- Only evidence-supported catalogue membership. It can differ from build inputs.
CREATE TABLE kb_asset_source (
  asset_id VARCHAR(255) NOT NULL,
  asset_version BIGINT NOT NULL,
  document_id VARCHAR(36) NOT NULL REFERENCES document(document_id),
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  evidence_ids JSONB NOT NULL CHECK(jsonb_typeof(evidence_ids)='array' AND jsonb_array_length(evidence_ids)>0),
  PRIMARY KEY(asset_id,asset_version,source_version_id),
  FOREIGN KEY(asset_id,asset_version) REFERENCES kb_derived_asset(asset_id,asset_version)
);
-- Written only for entry results actually expanded into the query's model evidence.
CREATE TABLE query_asset_expansion (
  session_id VARCHAR(36) NOT NULL REFERENCES query_session(session_id),
  asset_id VARCHAR(255) NOT NULL,
  asset_version BIGINT NOT NULL,
  source_version_id VARCHAR(36) NOT NULL,
  evidence_id VARCHAR(36) NOT NULL REFERENCES evidence(evidence_id),
  first_seen_round INT NOT NULL CHECK(first_seen_round>0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(session_id,asset_id,asset_version,evidence_id),
  FOREIGN KEY(asset_id,asset_version,source_version_id)
    REFERENCES kb_asset_source(asset_id,asset_version,source_version_id)
);
CREATE TABLE asset_usage_contribution (
  user_id VARCHAR(36) NOT NULL REFERENCES enterprise_user(user_id),
  session_id VARCHAR(36) NOT NULL REFERENCES query_session(session_id),
  asset_id VARCHAR(255) NOT NULL,
  asset_version BIGINT NOT NULL,
  receipt_id VARCHAR(36) NOT NULL REFERENCES package_download_receipt(receipt_id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id,session_id,asset_id),
  FOREIGN KEY(asset_id,asset_version) REFERENCES kb_derived_asset(asset_id,asset_version)
);
CREATE INDEX kb_asset_dependency_source ON kb_asset_dependency(source_version_id);
CREATE INDEX query_asset_expansion_source ON query_asset_expansion(session_id,source_version_id);
CREATE INDEX asset_usage_rolling_window ON asset_usage_contribution(asset_id,created_at);
