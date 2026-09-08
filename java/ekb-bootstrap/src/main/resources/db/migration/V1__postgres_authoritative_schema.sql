-- PostgreSQL authoritative schema for the isolated target deployment.

-- Legacy MySQL migrations are retained unchanged; Flyway owns this schema.

CREATE TABLE IF NOT EXISTS document (
  document_id VARCHAR(36) PRIMARY KEY,
  logical_name VARCHAR(512) NOT NULL,
  source_path VARCHAR(2048) NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','DELETED')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS document_version (
  version_id VARCHAR(36) PRIMARY KEY,
  document_id VARCHAR(36) NOT NULL,
  version_no BIGINT NOT NULL,
  lifecycle VARCHAR(64) NOT NULL DEFAULT 'PENDING' CHECK (lifecycle IN ('PENDING','ACTIVE','INACTIVE','FAILED')),
  original_filename VARCHAR(1024) NOT NULL,
  media_type VARCHAR(255) NULL,
  size_bytes BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  original_object_key VARCHAR(2048) NOT NULL,
  markdown_object_key VARCHAR(2048) NULL,
  structured_object_key VARCHAR(2048) NULL,
  parser_name VARCHAR(128) NULL,
  parser_version VARCHAR(128) NULL,
  parsed_at TIMESTAMP(6) WITH TIME ZONE NULL,
  activated_at TIMESTAMP(6) WITH TIME ZONE NULL,
  failure_code VARCHAR(128) NULL,
  failure_message TEXT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_version_document FOREIGN KEY (document_id) REFERENCES document(document_id)
);

CREATE TABLE IF NOT EXISTS document_unit (
  unit_id VARCHAR(36) PRIMARY KEY,
  version_id VARCHAR(36) NOT NULL,
  unit_no INT NOT NULL,
  title VARCHAR(1024) NULL,
  structure_type VARCHAR(64) NOT NULL DEFAULT 'single' CHECK (structure_type IN ('single','bundle','mixed')),
  document_type VARCHAR(128) NULL,
  manual_document_type_override VARCHAR(128) NULL,
  boundary_json JSONB NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_unit_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS document_metadata (
  version_id VARCHAR(36) PRIMARY KEY,
  source_department VARCHAR(255) NULL,
  material_category VARCHAR(255) NULL,
  manual_tags JSONB NULL,
  company VARCHAR(512) NULL,
  document_date DATE NULL,
  auto_document_type VARCHAR(128) NULL,
  manual_document_type_override VARCHAR(128) NULL,
  extra_auto_metadata JSONB NULL,
  extra_manual_metadata JSONB NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_metadata_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS raw_block (
  block_id VARCHAR(96) PRIMARY KEY,
  version_id VARCHAR(36) NOT NULL,
  block_no INT NOT NULL,
  block_type VARCHAR(64) NOT NULL,
  raw_content TEXT NOT NULL,
  normalized_content TEXT NULL,
  heading_path JSONB NULL,
  page_start INT NULL,
  page_end INT NULL,
  slide_no INT NULL,
  sheet_name VARCHAR(255) NULL,
  cell_range VARCHAR(128) NULL,
  bbox_json JSONB NULL,
  source_block_ids JSONB NULL,
  content_hash VARCHAR(64) NOT NULL,
  token_count INT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_raw_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS compile_task (
  task_id VARCHAR(36) PRIMARY KEY,
  version_id VARCHAR(36) NULL,
  task_type VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RETRY_WAIT','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  input_hash VARCHAR(64) NULL,
  output_hash VARCHAR(64) NULL,
  attempt INT NOT NULL DEFAULT 0,
  error_code VARCHAR(128) NULL,
  error_message TEXT NULL,
  started_at TIMESTAMP(6) WITH TIME ZONE NULL,
  finished_at TIMESTAMP(6) WITH TIME ZONE NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_compile_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS evidence (
  evidence_id VARCHAR(36) PRIMARY KEY,
  document_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  block_id VARCHAR(96) NOT NULL,
  object_key VARCHAR(2048) NOT NULL,
  raw_location JSONB NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_evidence_document FOREIGN KEY (document_id) REFERENCES document(document_id),
  CONSTRAINT fk_evidence_version FOREIGN KEY (version_id) REFERENCES document_version(version_id),
  CONSTRAINT fk_evidence_block FOREIGN KEY (block_id) REFERENCES raw_block(block_id)
);

CREATE TABLE IF NOT EXISTS distill_recommendation (
  recommendation_id VARCHAR(36) PRIMARY KEY,
  version_id VARCHAR(36) NOT NULL,
  reason VARCHAR(64) NOT NULL CHECK (reason IN ('USER_MARKED','EXISTING_QUESTION_EVIDENCE','RUNTIME_EFFECTIVE_HIT')),
  distinct_effective_hits INT NOT NULL DEFAULT 0,
  status VARCHAR(64) NOT NULL DEFAULT 'RECOMMENDED' CHECK (status IN ('RECOMMENDED','ACCEPTED','DISMISSED')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_distill_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS summary (
  summary_id VARCHAR(36) PRIMARY KEY,
  unit_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  object_key VARCHAR(2048) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  grounding_status VARCHAR(64) NOT NULL DEFAULT 'PENDING' CHECK (grounding_status IN ('PENDING','PASS','FAIL')),
  coverage_status VARCHAR(64) NOT NULL DEFAULT 'PENDING' CHECK (coverage_status IN ('PENDING','PASS','FAIL')),
  lifecycle VARCHAR(64) NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE','STALE','INACTIVE')),
  model VARCHAR(255) NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_summary_unit FOREIGN KEY (unit_id) REFERENCES document_unit(unit_id),
  CONSTRAINT fk_summary_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS summary_fact (
  fact_id VARCHAR(36) PRIMARY KEY,
  summary_id VARCHAR(36) NOT NULL,
  section VARCHAR(255) NOT NULL,
  fact_text TEXT NOT NULL,
  grounding_status VARCHAR(64) NOT NULL DEFAULT 'PENDING' CHECK (grounding_status IN ('PENDING','PASS','FAIL')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_fact_summary FOREIGN KEY (summary_id) REFERENCES summary(summary_id)
);

CREATE TABLE IF NOT EXISTS summary_evidence (
  fact_id VARCHAR(36) NOT NULL,
  evidence_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (fact_id, evidence_id),
  CONSTRAINT fk_summary_evidence_fact FOREIGN KEY (fact_id) REFERENCES summary_fact(fact_id),
  CONSTRAINT fk_summary_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
);

CREATE TABLE IF NOT EXISTS wiki (
  wiki_id VARCHAR(36) NOT NULL,
  wiki_version BIGINT NOT NULL,
  wiki_type VARCHAR(64) NOT NULL,
  canonical_subject_id VARCHAR(36) NULL,
  title VARCHAR(1024) NOT NULL,
  object_key VARCHAR(2048) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  lifecycle VARCHAR(64) NOT NULL DEFAULT 'UNDER_REVIEW' CHECK (lifecycle IN ('ACTIVE','STALE','INACTIVE','UNDER_REVIEW')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (wiki_id, wiki_version)
);

CREATE TABLE IF NOT EXISTS wiki_claim (
  claim_id VARCHAR(36) PRIMARY KEY,
  wiki_id VARCHAR(36) NOT NULL,
  wiki_version BIGINT NOT NULL,
  section VARCHAR(255) NOT NULL,
  claim_text TEXT NOT NULL,
  grounding_status VARCHAR(64) NOT NULL DEFAULT 'PENDING' CHECK (grounding_status IN ('PENDING','PASS','FAIL')),
  temporal_status VARCHAR(64) NOT NULL DEFAULT 'PENDING' CHECK (temporal_status IN ('PENDING','PASS','CONFLICT')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_claim_wiki FOREIGN KEY (wiki_id, wiki_version) REFERENCES wiki(wiki_id, wiki_version)
);

CREATE TABLE IF NOT EXISTS wiki_claim_fact (
  claim_id VARCHAR(36) NOT NULL,
  fact_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (claim_id, fact_id),
  CONSTRAINT fk_wiki_claim_fact_claim FOREIGN KEY (claim_id) REFERENCES wiki_claim(claim_id),
  CONSTRAINT fk_wiki_claim_fact_fact FOREIGN KEY (fact_id) REFERENCES summary_fact(fact_id)
);

CREATE TABLE IF NOT EXISTS wiki_evidence (
  claim_id VARCHAR(36) NOT NULL,
  evidence_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (claim_id, evidence_id),
  CONSTRAINT fk_wiki_evidence_claim FOREIGN KEY (claim_id) REFERENCES wiki_claim(claim_id),
  CONSTRAINT fk_wiki_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
);

CREATE TABLE IF NOT EXISTS source_entity (
  source_entity_id VARCHAR(36) PRIMARY KEY,
  version_id VARCHAR(36) NOT NULL,
  surface_form VARCHAR(1024) NOT NULL,
  entity_type VARCHAR(128) NOT NULL,
  evidence_id VARCHAR(36) NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_source_entity_version FOREIGN KEY (version_id) REFERENCES document_version(version_id),
  CONSTRAINT fk_source_entity_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
);

CREATE TABLE IF NOT EXISTS canonical_entity (
  canonical_entity_id VARCHAR(36) PRIMARY KEY,
  canonical_name VARCHAR(1024) NOT NULL,
  entity_type VARCHAR(128) NOT NULL,
  canonical_key VARCHAR(1024) NOT NULL,
  attributes_json JSONB NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS entity_mapping (
  source_entity_id VARCHAR(36) PRIMARY KEY,
  canonical_entity_id VARCHAR(36) NOT NULL,
  confidence DECIMAL(6,5) NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPLIED','REJECTED')),
  decided_by VARCHAR(255) NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_mapping_source FOREIGN KEY (source_entity_id) REFERENCES source_entity(source_entity_id),
  CONSTRAINT fk_mapping_canonical FOREIGN KEY (canonical_entity_id) REFERENCES canonical_entity(canonical_entity_id)
);

CREATE TABLE IF NOT EXISTS relation_fact (
  relation_id VARCHAR(36) PRIMARY KEY,
  source_entity_id VARCHAR(36) NOT NULL,
  relation_type VARCHAR(128) NOT NULL,
  target_entity_id VARCHAR(36) NOT NULL,
  valid_from TIMESTAMP(6) WITH TIME ZONE NULL,
  valid_to TIMESTAMP(6) WITH TIME ZONE NULL,
  conflict_status VARCHAR(64) NOT NULL DEFAULT 'NONE' CHECK (conflict_status IN ('NONE','POTENTIAL','CONFIRMED')),
  lifecycle VARCHAR(64) NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE','INACTIVE')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_relation_source FOREIGN KEY (source_entity_id) REFERENCES canonical_entity(canonical_entity_id),
  CONSTRAINT fk_relation_target FOREIGN KEY (target_entity_id) REFERENCES canonical_entity(canonical_entity_id)
);

CREATE TABLE IF NOT EXISTS relation_evidence_support (
  support_id VARCHAR(36) PRIMARY KEY,
  relation_id VARCHAR(36) NOT NULL,
  origin VARCHAR(64) NOT NULL CHECK (origin IN ('llm_triple','wiki_keyword','alias_derived')),
  evidence_id VARCHAR(36) NOT NULL,
  summary_id VARCHAR(36) NULL,
  wiki_id VARCHAR(36) NULL,
  wiki_version BIGINT NULL,
  confidence DECIMAL(6,5) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_support_relation FOREIGN KEY (relation_id) REFERENCES relation_fact(relation_id),
  CONSTRAINT fk_support_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
);

CREATE TABLE IF NOT EXISTS knowledge_event (
  event_id VARCHAR(36) PRIMARY KEY,
  event_type VARCHAR(128) NOT NULL,
  subject_entity_id VARCHAR(36) NULL,
  object_entity_id VARCHAR(36) NULL,
  occurred_at TIMESTAMP(6) WITH TIME ZONE NULL,
  valid_from TIMESTAMP(6) WITH TIME ZONE NULL,
  valid_to TIMESTAMP(6) WITH TIME ZONE NULL,
  payload_json JSONB NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS search_record (
  search_id VARCHAR(36) PRIMARY KEY,
  query_text TEXT NOT NULL,
  filters_json JSONB NULL,
  mode VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS search_result (
  search_id VARCHAR(36) NOT NULL,
  rank_no INT NOT NULL,
  result_type VARCHAR(64) NOT NULL CHECK (result_type IN ('RAW','SUMMARY','WIKI','GRAPH')),
  result_id VARCHAR(255) NOT NULL,
  score DOUBLE PRECISION NOT NULL,
  evidence_ids JSONB NOT NULL,
  effective BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (search_id, rank_no),
  CONSTRAINT fk_search_result_record FOREIGN KEY (search_id) REFERENCES search_record(search_id)
);

CREATE TABLE IF NOT EXISTS effective_version_hit (
  search_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (search_id, version_id),
  CONSTRAINT fk_effective_hit_search FOREIGN KEY (search_id) REFERENCES search_record(search_id),
  CONSTRAINT fk_effective_hit_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS package_record (
  package_id VARCHAR(36) PRIMARY KEY,
  object_key VARCHAR(2048) NOT NULL,
  manifest_json JSONB NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS rule_version (
  rule_id VARCHAR(128) NOT NULL,
  version_no BIGINT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  config_json JSONB NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (rule_id, version_no)
);

CREATE TABLE IF NOT EXISTS enterprise_user (
  user_id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(64) NOT NULL DEFAULT 'USER' CHECK (role IN ('ADMIN','USER')),
  status VARCHAR(64) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  last_login_at TIMESTAMP(6) WITH TIME ZONE NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS enterprise_user_session (
  session_hash VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  last_seen_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_enterprise_session_user FOREIGN KEY (user_id) REFERENCES enterprise_user(user_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS enterprise_setting (
  setting_key VARCHAR(128) PRIMARY KEY,
  setting_value VARCHAR(512) NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS ingestion_batch (
  batch_id VARCHAR(36) PRIMARY KEY,
  created_by VARCHAR(36) NULL,
  created_by_username VARCHAR(64) NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'STAGED' CHECK (status IN ('STAGED','QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED')),
  source_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  started_at TIMESTAMP(6) WITH TIME ZONE NULL,
  finished_at TIMESTAMP(6) WITH TIME ZONE NULL
);

CREATE TABLE IF NOT EXISTS ingestion_file (
  file_id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  relative_path VARCHAR(1024) NOT NULL,
  original_filename VARCHAR(512) NOT NULL,
  staging_path VARCHAR(2048) NOT NULL,
  size_bytes BIGINT NOT NULL DEFAULT 0,
  sha256 VARCHAR(64) NOT NULL,
  logical_name VARCHAR(512) NOT NULL,
  source_department VARCHAR(255) NOT NULL DEFAULT '',
  material_category VARCHAR(255) NOT NULL DEFAULT '',
  company VARCHAR(255) NOT NULL DEFAULT '',
  document_type VARCHAR(128) NOT NULL DEFAULT '',
  document_date DATE NULL,
  manual_tags JSONB NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'STAGED' CHECK (status IN ('STAGED','QUEUED','RUNNING','SUCCEEDED','FAILED')),
  progress_stage VARCHAR(64) NOT NULL DEFAULT 'STAGED',
  attempt INT NOT NULL DEFAULT 0,
  document_id VARCHAR(36) NULL,
  version_id VARCHAR(36) NULL,
  error_code VARCHAR(128) NOT NULL DEFAULT '',
  error_message TEXT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  started_at TIMESTAMP(6) WITH TIME ZONE NULL,
  finished_at TIMESTAMP(6) WITH TIME ZONE NULL,
  CONSTRAINT fk_ingestion_file_batch FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_event (
  event_id VARCHAR(36) PRIMARY KEY,
  actor_user_id VARCHAR(36) NULL,
  actor_username VARCHAR(64) NOT NULL,
  actor_role VARCHAR(32) NOT NULL,
  action VARCHAR(128) NOT NULL,
  target_type VARCHAR(64) NOT NULL DEFAULT '',
  target_id VARCHAR(255) NOT NULL DEFAULT '',
  target_name VARCHAR(512) NOT NULL DEFAULT '',
  outcome VARCHAR(64) NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS','FAILURE')),
  request_id VARCHAR(64) NOT NULL DEFAULT '',
  ip_address VARCHAR(64) NOT NULL DEFAULT '',
  user_agent VARCHAR(512) NOT NULL DEFAULT '',
  details_json JSONB NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS query_session (
  session_id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  original_question TEXT NOT NULL,
  filters_json JSONB NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','WAITING_USER','ANSWERED','INSUFFICIENT','FAILED')),
  final_answer TEXT NULL,
  evidence_only BOOLEAN NOT NULL DEFAULT FALSE,
  clarification_question TEXT NULL,
  choices_json JSONB NOT NULL,
  citation_evidence_ids JSONB NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_at TIMESTAMP(6) WITH TIME ZONE NULL
);

CREATE TABLE IF NOT EXISTS query_turn (
  turn_id VARCHAR(36) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  role VARCHAR(64) NOT NULL CHECK (role IN ('USER','ASSISTANT','SYSTEM_EVENT')),
  content TEXT NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_query_turn_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS retrieval_round (
  round_id VARCHAR(36) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  round_no INT NOT NULL,
  input_queries JSONB NOT NULL,
  decision VARCHAR(64) NOT NULL CHECK (decision IN ('ANSWER','CONTINUE_SEARCH','ASK_USER')),
  missing_information JSONB NOT NULL,
  next_queries JSONB NOT NULL,
  started_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  finished_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_retrieval_round_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS query_evidence (
  session_id VARCHAR(36) NOT NULL,
  evidence_id VARCHAR(36) NOT NULL,
  first_seen_round INT NOT NULL,
  supporting_query TEXT NOT NULL,
  PRIMARY KEY (session_id, evidence_id),
  CONSTRAINT fk_query_evidence_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS query_feedback (
  feedback_id VARCHAR(36) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  rating VARCHAR(64) NOT NULL CHECK (rating IN ('POSITIVE','NEGATIVE','NEUTRAL')),
  category VARCHAR(64) NULL,
  comment TEXT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_query_feedback_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS query_group (
  group_id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_at TIMESTAMP(6) WITH TIME ZONE NULL
);

CREATE TABLE IF NOT EXISTS query_group_member (
  group_id VARCHAR(36) NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  position INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_at TIMESTAMP(6) WITH TIME ZONE NULL,
  PRIMARY KEY (group_id, session_id),
  CONSTRAINT fk_query_group_member_group FOREIGN KEY (group_id) REFERENCES query_group(group_id) ON DELETE CASCADE,
  CONSTRAINT fk_query_group_member_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS query_package (
  package_id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  owner_type VARCHAR(64) NOT NULL CHECK (owner_type IN ('SESSION','GROUP')),
  owner_id VARCHAR(36) NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','DELETED','MISSING')),
  deleted_at TIMESTAMP(6) WITH TIME ZONE NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS model_configuration (
  configuration_id SMALLINT PRIMARY KEY,
  chat_base_url VARCHAR(2048) NOT NULL,
  chat_api_key TEXT NULL,
  small_model VARCHAR(255) NOT NULL,
  large_model VARCHAR(255) NOT NULL,
  chat_timeout_seconds INT NOT NULL,
  embedding_base_url VARCHAR(2048) NOT NULL,
  embedding_api_key TEXT NULL,
  embedding_model VARCHAR(255) NOT NULL,
  embedding_dimensions INT NOT NULL,
  embedding_batch_size INT NOT NULL,
  embedding_timeout_seconds INT NOT NULL,
  rerank_base_url VARCHAR(2048) NOT NULL,
  rerank_api_key TEXT NULL,
  rerank_model VARCHAR(255) NOT NULL,
  rerank_timeout_seconds INT NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS model_configuration_profile (
  profile_id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  chat_base_url VARCHAR(2048) NOT NULL,
  chat_api_key TEXT NULL,
  small_model VARCHAR(255) NOT NULL,
  large_model VARCHAR(255) NOT NULL,
  chat_timeout_seconds INT NOT NULL,
  embedding_base_url VARCHAR(2048) NOT NULL,
  embedding_api_key TEXT NULL,
  embedding_model VARCHAR(255) NOT NULL,
  embedding_dimensions INT NOT NULL,
  embedding_batch_size INT NOT NULL,
  embedding_timeout_seconds INT NOT NULL,
  rerank_base_url VARCHAR(2048) NOT NULL,
  rerank_api_key TEXT NULL,
  rerank_model VARCHAR(255) NOT NULL,
  rerank_timeout_seconds INT NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS concept_candidate (
  concept_id VARCHAR(36) PRIMARY KEY,
  summary_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  preferred_label VARCHAR(1024) NOT NULL,
  concept_type_hint VARCHAR(128) NOT NULL DEFAULT 'OTHER',
  description TEXT NULL,
  lifecycle VARCHAR(64) NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE','STALE','INACTIVE')),
  model VARCHAR(255) NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_concept_summary FOREIGN KEY (summary_id) REFERENCES summary(summary_id),
  CONSTRAINT fk_concept_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
);

CREATE TABLE IF NOT EXISTS concept_alias (
  alias_id VARCHAR(36) PRIMARY KEY,
  concept_id VARCHAR(36) NOT NULL,
  alias_text VARCHAR(1024) NOT NULL,
  alias_key VARCHAR(64) NOT NULL,
  CONSTRAINT fk_concept_alias_concept FOREIGN KEY (concept_id) REFERENCES concept_candidate(concept_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS concept_fact_support (
  concept_id VARCHAR(36) NOT NULL,
  fact_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (concept_id, fact_id),
  CONSTRAINT fk_concept_fact_concept FOREIGN KEY (concept_id) REFERENCES concept_candidate(concept_id) ON DELETE CASCADE,
  CONSTRAINT fk_concept_fact_fact FOREIGN KEY (fact_id) REFERENCES summary_fact(fact_id)
);

CREATE TABLE IF NOT EXISTS graph_relation_rejection (
  rejection_id VARCHAR(36) PRIMARY KEY,
  summary_id VARCHAR(36) NOT NULL,
  candidate_index INT NOT NULL,
  source_name VARCHAR(1024) NULL,
  source_type VARCHAR(128) NULL,
  relation_type VARCHAR(128) NULL,
  target_name VARCHAR(1024) NULL,
  target_type VARCHAR(128) NULL,
  supporting_fact_ids JSONB NOT NULL,
  reason VARCHAR(2048) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_graph_rejection_summary FOREIGN KEY (summary_id) REFERENCES summary(summary_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS embedding_rebuild_checkpoint (
  version_id VARCHAR(36) NOT NULL,
  chunk_id VARCHAR(255) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  evidence_fingerprint VARCHAR(64) NOT NULL,
  evidence_ids TEXT NOT NULL,
  strategy_key VARCHAR(128) NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (version_id, chunk_id)
);

CREATE TABLE IF NOT EXISTS compile_task_failure (
  task_id VARCHAR(36) NOT NULL,
  attempt INT NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  task_type VARCHAR(64) NOT NULL,
  subject_id VARCHAR(255) NULL,
  outcome VARCHAR(64) NOT NULL CHECK (outcome IN ('RETRY_WAIT','FAILED')),
  error_code VARCHAR(128) NOT NULL,
  error_message TEXT NULL,
  occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  next_attempt_at TIMESTAMP(6) WITH TIME ZONE NULL,
  PRIMARY KEY (task_id, attempt),
  CONSTRAINT fk_compile_failure_task FOREIGN KEY (task_id) REFERENCES compile_task(task_id)
);

CREATE INDEX IF NOT EXISTS idx_document_name ON document (left(logical_name,191));
CREATE UNIQUE INDEX IF NOT EXISTS uq_document_version_no ON document_version (document_id, version_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_document_sha ON document_version (document_id, sha256);
CREATE INDEX IF NOT EXISTS idx_version_lifecycle ON document_version (lifecycle, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_version_unit_no ON document_unit (version_id, unit_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_version_block_no ON raw_block (version_id, block_no);
CREATE INDEX IF NOT EXISTS idx_raw_version ON raw_block (version_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_compile_idempotency ON compile_task (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_compile_status ON compile_task (status, task_type);
CREATE INDEX IF NOT EXISTS idx_evidence_version ON evidence (version_id, active);
CREATE INDEX IF NOT EXISTS idx_evidence_block ON evidence (block_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_distill_version_reason ON distill_recommendation (version_id, reason);
CREATE INDEX IF NOT EXISTS idx_summary_version ON summary (version_id, lifecycle);
CREATE INDEX IF NOT EXISTS idx_summary_fact_summary ON summary_fact (summary_id);
CREATE INDEX IF NOT EXISTS idx_wiki_title ON wiki (left(title,191), lifecycle);
CREATE INDEX IF NOT EXISTS idx_claim_wiki ON wiki_claim (wiki_id, wiki_version);
CREATE UNIQUE INDEX IF NOT EXISTS uq_canonical_type_key ON canonical_entity (entity_type, left(canonical_key,191));
CREATE UNIQUE INDEX IF NOT EXISTS uq_relation_identity ON relation_fact (source_entity_id, relation_type, target_entity_id, valid_from);
CREATE INDEX IF NOT EXISTS idx_relation_support_active ON relation_evidence_support (relation_id, active);
CREATE INDEX IF NOT EXISTS idx_search_result_effective ON search_result (effective);
CREATE INDEX IF NOT EXISTS idx_effective_version_hit_version ON effective_version_hit (version_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_enterprise_user_username ON enterprise_user (username);
CREATE INDEX IF NOT EXISTS idx_enterprise_session_user ON enterprise_user_session (user_id);
CREATE INDEX IF NOT EXISTS idx_enterprise_session_expiry ON enterprise_user_session (expires_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_batch_created ON ingestion_batch (created_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_batch_actor ON ingestion_batch (created_by, created_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_batch_status ON ingestion_batch (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_file_batch ON ingestion_file (batch_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_file_status ON ingestion_file (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_file_sha ON ingestion_file (sha256);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_event (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_event (actor_username, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_event (action, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_target ON audit_event (target_type, target_id, created_at);
CREATE INDEX IF NOT EXISTS idx_query_session_user ON query_session (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_query_session_status ON query_session (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_query_session_deleted ON query_session (user_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_query_turn_session ON query_turn (session_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_retrieval_round_session_no ON retrieval_round (session_id, round_no);
CREATE INDEX IF NOT EXISTS idx_retrieval_round_session ON retrieval_round (session_id, started_at);
CREATE INDEX IF NOT EXISTS idx_query_evidence_evidence ON query_evidence (evidence_id);
CREATE INDEX IF NOT EXISTS idx_query_feedback_session ON query_feedback (session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_query_feedback_user ON query_feedback (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_query_group_user ON query_group (user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_query_group_deleted ON query_group (user_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_query_group_member_session ON query_group_member (session_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_query_package_owner ON query_package (user_id, owner_type, owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_model_configuration_profile_active ON model_configuration_profile (active);
CREATE INDEX IF NOT EXISTS idx_concept_summary ON concept_candidate (summary_id, lifecycle);
CREATE INDEX IF NOT EXISTS idx_concept_version ON concept_candidate (version_id, lifecycle);
CREATE UNIQUE INDEX IF NOT EXISTS uq_concept_alias_key ON concept_alias (concept_id, alias_key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_graph_rejection_candidate ON graph_relation_rejection (summary_id, candidate_index);
CREATE INDEX IF NOT EXISTS idx_graph_rejection_summary ON graph_relation_rejection (summary_id, created_at);
CREATE INDEX IF NOT EXISTS idx_embedding_checkpoint_version ON embedding_rebuild_checkpoint (version_id, updated_at);

ALTER TABLE compile_task ADD COLUMN IF NOT EXISTS subject_id VARCHAR(255);
ALTER TABLE compile_task ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP(6) WITH TIME ZONE;
CREATE INDEX IF NOT EXISTS idx_compile_version_type_status ON compile_task(version_id, task_type, status);
CREATE INDEX IF NOT EXISTS idx_compile_due ON compile_task(status, next_attempt_at, created_at, task_id);
ALTER TABLE ingestion_file ADD COLUMN IF NOT EXISTS parser_task_id VARCHAR(128);
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS security_level SMALLINT NOT NULL DEFAULT 10 CHECK (security_level BETWEEN 10 AND 127);
ALTER TABLE document_metadata ADD COLUMN IF NOT EXISTS acl_version BIGINT NOT NULL DEFAULT 1 CHECK (acl_version > 0);
ALTER TABLE enterprise_user ADD COLUMN IF NOT EXISTS security_clearance SMALLINT NOT NULL DEFAULT 10 CHECK (security_clearance BETWEEN 10 AND 127);
CREATE TABLE IF NOT EXISTS identity_coordination (singleton SMALLINT PRIMARY KEY CHECK (singleton=1));
INSERT INTO identity_coordination(singleton) VALUES(1) ON CONFLICT DO NOTHING;
CREATE UNIQUE INDEX IF NOT EXISTS uq_model_active_profile ON model_configuration_profile(active) WHERE active;
