-- Current source authority is reused for retrieval, browsing, history and streaming.
-- Missing metadata and inactive versions fail closed. A newer current classification
-- also protects older versions; an old confidential version cannot be declassified
-- merely by uploading a public successor.
CREATE VIEW kb_source_authority AS
SELECT v.version_id, v.document_id,
       GREATEST(m.security_level, current_metadata.security_level) AS security_level,
       m.acl_version
FROM document_version v
JOIN document d ON d.document_id=v.document_id AND d.status='ACTIVE'
JOIN document_metadata m ON m.version_id=v.version_id
JOIN LATERAL (
  SELECT latest_m.security_level FROM document_version latest_v
  JOIN document_metadata latest_m ON latest_m.version_id=latest_v.version_id
  WHERE latest_v.document_id=v.document_id AND latest_v.lifecycle='ACTIVE'
  ORDER BY latest_v.version_no DESC LIMIT 1
) current_metadata ON TRUE
WHERE v.lifecycle='ACTIVE';

-- Object keys and source IDs stay server-side. The browser receives an authenticated
-- application URL, never a bearer URL that bypasses later revocation.
CREATE TABLE source_package_access (
  package_id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL REFERENCES enterprise_user(user_id),
  object_key VARCHAR(2048) NOT NULL UNIQUE,
  evidence_ids JSONB NOT NULL CHECK (jsonb_typeof(evidence_ids)='array'),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMPTZ
);
CREATE INDEX source_package_owner ON source_package_access(user_id, package_id) WHERE deleted_at IS NULL;

-- Retain every source dependency used by the model, even if the final answer did
-- not cite it. Restoring a previous answer must not rely on citation-only ACLs.
CREATE TABLE query_source_dependency (
  session_id VARCHAR(36) NOT NULL REFERENCES query_session(session_id),
  version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  PRIMARY KEY(session_id, version_id)
);
