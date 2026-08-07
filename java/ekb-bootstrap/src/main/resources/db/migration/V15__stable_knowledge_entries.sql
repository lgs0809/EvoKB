-- Do not reinterpret historic per-summary Concepts as proven cross-source identities.
-- New entries are populated through ordinary grounded compilation, not migration-created success.
CREATE TABLE kb_knowledge_entry (
  knowledge_base_id VARCHAR(255) NOT NULL,
  entry_id VARCHAR(36) NOT NULL,
  revision BIGINT NOT NULL CHECK(revision>0),
  scope_key VARCHAR(255) NOT NULL,
  scope_json JSONB NOT NULL,
  entry_type VARCHAR(64) NOT NULL,
  preferred_label VARCHAR(1024) NOT NULL,
  aliases JSONB NOT NULL CHECK(jsonb_typeof(aliases)='array'),
  description TEXT NOT NULL,
  stage VARCHAR(16) NOT NULL CHECK(stage IN ('CONCEPT','WIKI')),
  lifecycle VARCHAR(16) NOT NULL CHECK(lifecycle IN ('ACTIVE','STALE','INACTIVE')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(knowledge_base_id,entry_id)
);
CREATE TABLE kb_entry_source (
  knowledge_base_id VARCHAR(255) NOT NULL,
  entry_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL REFERENCES document(document_id),
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  summary_id VARCHAR(36) NOT NULL REFERENCES summary(summary_id),
  concept_id VARCHAR(36) NOT NULL REFERENCES concept_candidate(concept_id),
  acl_version BIGINT NOT NULL CHECK(acl_version>0),
  fact_ids JSONB NOT NULL CHECK(jsonb_typeof(fact_ids)='array' AND jsonb_array_length(fact_ids)>0),
  PRIMARY KEY(knowledge_base_id,entry_id,concept_id),
  FOREIGN KEY(knowledge_base_id,entry_id) REFERENCES kb_knowledge_entry(knowledge_base_id,entry_id)
);
CREATE INDEX kb_entry_sources_version ON kb_entry_source(source_version_id);
CREATE INDEX kb_entry_candidates ON kb_knowledge_entry(knowledge_base_id,lifecycle,updated_at,entry_id);
-- Membership is not the complete dependency set: candidate metadata also entered the model.
CREATE TABLE kb_entry_build_input (
  knowledge_base_id VARCHAR(255) NOT NULL,
  entry_id VARCHAR(36) NOT NULL,
  entry_revision BIGINT NOT NULL CHECK(entry_revision>0),
  summary_id VARCHAR(36) NOT NULL REFERENCES summary(summary_id),
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  acl_version BIGINT NOT NULL CHECK(acl_version>0),
  PRIMARY KEY(knowledge_base_id,entry_id,entry_revision,summary_id),
  FOREIGN KEY(knowledge_base_id,entry_id) REFERENCES kb_knowledge_entry(knowledge_base_id,entry_id)
);
CREATE INDEX kb_entry_build_sources ON kb_entry_build_input(source_version_id);
