-- Preserve historic search hits, but never reinterpret them as completed downloads.
-- Every new package has an immutable size and source-version manifest, with optional answer attribution.
CREATE TABLE package_usage_manifest (
  package_id VARCHAR(36) PRIMARY KEY REFERENCES source_package_access(package_id),
  size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE package_manifest_source (
  package_id VARCHAR(36) NOT NULL REFERENCES package_usage_manifest(package_id),
  document_id VARCHAR(36) NOT NULL REFERENCES document(document_id),
  version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  PRIMARY KEY(package_id, version_id)
);
CREATE TABLE package_answer_source (
  package_id VARCHAR(36) NOT NULL,
  session_id VARCHAR(36) NOT NULL REFERENCES query_session(session_id),
  version_id VARCHAR(36) NOT NULL,
  PRIMARY KEY(package_id, session_id, version_id),
  FOREIGN KEY(package_id, version_id) REFERENCES package_manifest_source(package_id, version_id)
);
CREATE TABLE package_download_receipt (
  receipt_id VARCHAR(36) PRIMARY KEY,
  package_id VARCHAR(36) NOT NULL REFERENCES package_usage_manifest(package_id),
  user_id VARCHAR(64) NOT NULL REFERENCES enterprise_user(user_id),
  transferred_bytes BIGINT NOT NULL CHECK (transferred_bytes > 0),
  completed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(package_id, user_id)
);
CREATE TABLE source_usage_contribution (
  user_id VARCHAR(64) NOT NULL REFERENCES enterprise_user(user_id),
  session_id VARCHAR(36) NOT NULL REFERENCES query_session(session_id),
  document_id VARCHAR(36) NOT NULL REFERENCES document(document_id),
  version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  receipt_id VARCHAR(36) NOT NULL REFERENCES package_download_receipt(receipt_id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(user_id, session_id, document_id, version_id)
);
CREATE INDEX source_usage_recent ON source_usage_contribution(version_id, created_at);
-- This is source usage only. Concept usage needs its own actual expansion provenance;
-- no entry receives heat merely because it is associated with a downloaded document.
