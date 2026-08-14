CREATE DATABASE IF NOT EXISTS enterprise_kb CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE enterprise_kb;

CREATE TABLE IF NOT EXISTS document (
  document_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  logical_name VARCHAR(512) NOT NULL,
  source_path VARCHAR(2048) NULL,
  status ENUM('ACTIVE','INACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY idx_document_name (logical_name(191))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS document_version (
  version_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  document_id CHAR(36) CHARACTER SET ascii NOT NULL,
  version_no BIGINT UNSIGNED NOT NULL,
  lifecycle ENUM('PENDING','ACTIVE','INACTIVE','FAILED') NOT NULL DEFAULT 'PENDING',
  original_filename VARCHAR(1024) NOT NULL,
  media_type VARCHAR(255) NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
  original_object_key VARCHAR(2048) NOT NULL,
  markdown_object_key VARCHAR(2048) NULL,
  structured_object_key VARCHAR(2048) NULL,
  parser_name VARCHAR(128) NULL,
  parser_version VARCHAR(128) NULL,
  parsed_at TIMESTAMP(6) NULL,
  activated_at TIMESTAMP(6) NULL,
  failure_code VARCHAR(128) NULL,
  failure_message TEXT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_document_version_no (document_id, version_no),
  UNIQUE KEY uq_document_sha (document_id, sha256),
  KEY idx_version_lifecycle (lifecycle, created_at),
  CONSTRAINT fk_version_document FOREIGN KEY (document_id) REFERENCES document(document_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS document_unit (
  unit_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  unit_no INT UNSIGNED NOT NULL,
  title VARCHAR(1024) NULL,
  structure_type ENUM('single','bundle','mixed') NOT NULL DEFAULT 'single',
  document_type VARCHAR(128) NULL,
  manual_document_type_override VARCHAR(128) NULL,
  boundary_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_version_unit_no (version_id, unit_no),
  CONSTRAINT fk_unit_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS document_metadata (
  version_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  source_department VARCHAR(255) NULL,
  material_category VARCHAR(255) NULL,
  manual_tags JSON NULL,
  company VARCHAR(512) NULL,
  document_date DATE NULL,
  auto_document_type VARCHAR(128) NULL,
  manual_document_type_override VARCHAR(128) NULL,
  extra_auto_metadata JSON NULL,
  extra_manual_metadata JSON NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_metadata_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS raw_block (
  block_id VARCHAR(96) CHARACTER SET ascii PRIMARY KEY,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  block_no INT UNSIGNED NOT NULL,
  block_type VARCHAR(64) NOT NULL,
  raw_content LONGTEXT NOT NULL,
  normalized_content LONGTEXT NULL,
  heading_path JSON NULL,
  page_start INT NULL,
  page_end INT NULL,
  slide_no INT NULL,
  sheet_name VARCHAR(255) NULL,
  cell_range VARCHAR(128) NULL,
  bbox_json JSON NULL,
  source_block_ids JSON NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  token_count INT UNSIGNED NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_version_block_no (version_id, block_no),
  KEY idx_raw_version (version_id),
  CONSTRAINT fk_raw_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS compile_task (
  task_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  version_id CHAR(36) CHARACTER SET ascii NULL,
  task_type VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(255) CHARACTER SET ascii NOT NULL,
  status ENUM('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  input_hash CHAR(64) CHARACTER SET ascii NULL,
  output_hash CHAR(64) CHARACTER SET ascii NULL,
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  error_code VARCHAR(128) NULL,
  error_message TEXT NULL,
  started_at TIMESTAMP(6) NULL,
  finished_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_compile_idempotency (idempotency_key),
  KEY idx_compile_status (status, task_type),
  CONSTRAINT fk_compile_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS evidence (
  evidence_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  document_id CHAR(36) CHARACTER SET ascii NOT NULL,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  block_id VARCHAR(96) CHARACTER SET ascii NOT NULL,
  object_key VARCHAR(2048) NOT NULL,
  raw_location JSON NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_evidence_version (version_id, active),
  KEY idx_evidence_block (block_id),
  CONSTRAINT fk_evidence_document FOREIGN KEY (document_id) REFERENCES document(document_id),
  CONSTRAINT fk_evidence_version FOREIGN KEY (version_id) REFERENCES document_version(version_id),
  CONSTRAINT fk_evidence_block FOREIGN KEY (block_id) REFERENCES raw_block(block_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS distill_recommendation (
  recommendation_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  reason ENUM('USER_MARKED','EXISTING_QUESTION_EVIDENCE','RUNTIME_EFFECTIVE_HIT') NOT NULL,
  distinct_effective_hits INT UNSIGNED NOT NULL DEFAULT 0,
  status ENUM('RECOMMENDED','ACCEPTED','DISMISSED') NOT NULL DEFAULT 'RECOMMENDED',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_distill_version_reason (version_id, reason),
  CONSTRAINT fk_distill_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS summary (
  summary_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  unit_id CHAR(36) CHARACTER SET ascii NOT NULL,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  object_key VARCHAR(2048) NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  grounding_status ENUM('PENDING','PASS','FAIL') NOT NULL DEFAULT 'PENDING',
  coverage_status ENUM('PENDING','PASS','FAIL') NOT NULL DEFAULT 'PENDING',
  lifecycle ENUM('ACTIVE','STALE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  model VARCHAR(255) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_summary_version (version_id, lifecycle),
  CONSTRAINT fk_summary_unit FOREIGN KEY (unit_id) REFERENCES document_unit(unit_id),
  CONSTRAINT fk_summary_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS summary_fact (
  fact_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  summary_id CHAR(36) CHARACTER SET ascii NOT NULL,
  section VARCHAR(255) NOT NULL,
  fact_text TEXT NOT NULL,
  grounding_status ENUM('PENDING','PASS','FAIL') NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_summary_fact_summary (summary_id),
  CONSTRAINT fk_fact_summary FOREIGN KEY (summary_id) REFERENCES summary(summary_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS summary_evidence (
  fact_id CHAR(36) CHARACTER SET ascii NOT NULL,
  evidence_id CHAR(36) CHARACTER SET ascii NOT NULL,
  PRIMARY KEY (fact_id, evidence_id),
  CONSTRAINT fk_summary_evidence_fact FOREIGN KEY (fact_id) REFERENCES summary_fact(fact_id),
  CONSTRAINT fk_summary_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wiki (
  wiki_id CHAR(36) CHARACTER SET ascii NOT NULL,
  wiki_version BIGINT UNSIGNED NOT NULL,
  wiki_type VARCHAR(64) NOT NULL,
  canonical_subject_id CHAR(36) CHARACTER SET ascii NULL,
  title VARCHAR(1024) NOT NULL,
  object_key VARCHAR(2048) NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  lifecycle ENUM('ACTIVE','STALE','INACTIVE','UNDER_REVIEW') NOT NULL DEFAULT 'UNDER_REVIEW',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (wiki_id, wiki_version),
  KEY idx_wiki_title (title(191), lifecycle)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wiki_claim (
  claim_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  wiki_id CHAR(36) CHARACTER SET ascii NOT NULL,
  wiki_version BIGINT UNSIGNED NOT NULL,
  section VARCHAR(255) NOT NULL,
  claim_text TEXT NOT NULL,
  grounding_status ENUM('PENDING','PASS','FAIL') NOT NULL DEFAULT 'PENDING',
  temporal_status ENUM('PENDING','PASS','CONFLICT') NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_claim_wiki (wiki_id, wiki_version),
  CONSTRAINT fk_claim_wiki FOREIGN KEY (wiki_id, wiki_version) REFERENCES wiki(wiki_id, wiki_version)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wiki_claim_fact (
  claim_id CHAR(36) CHARACTER SET ascii NOT NULL,
  fact_id CHAR(36) CHARACTER SET ascii NOT NULL,
  PRIMARY KEY (claim_id, fact_id),
  CONSTRAINT fk_wiki_claim_fact_claim FOREIGN KEY (claim_id) REFERENCES wiki_claim(claim_id),
  CONSTRAINT fk_wiki_claim_fact_fact FOREIGN KEY (fact_id) REFERENCES summary_fact(fact_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wiki_evidence (
  claim_id CHAR(36) CHARACTER SET ascii NOT NULL,
  evidence_id CHAR(36) CHARACTER SET ascii NOT NULL,
  PRIMARY KEY (claim_id, evidence_id),
  CONSTRAINT fk_wiki_evidence_claim FOREIGN KEY (claim_id) REFERENCES wiki_claim(claim_id),
  CONSTRAINT fk_wiki_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS source_entity (
  source_entity_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  surface_form VARCHAR(1024) NOT NULL,
  entity_type VARCHAR(128) NOT NULL,
  evidence_id CHAR(36) CHARACTER SET ascii NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_source_entity_version FOREIGN KEY (version_id) REFERENCES document_version(version_id),
  CONSTRAINT fk_source_entity_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS canonical_entity (
  canonical_entity_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  canonical_name VARCHAR(1024) NOT NULL,
  entity_type VARCHAR(128) NOT NULL,
  canonical_key VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  attributes_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_canonical_type_key (entity_type, canonical_key(191))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS entity_mapping (
  source_entity_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  canonical_entity_id CHAR(36) CHARACTER SET ascii NOT NULL,
  confidence DECIMAL(6,5) NULL,
  status ENUM('PROPOSED','APPLIED','REJECTED') NOT NULL DEFAULT 'PROPOSED',
  decided_by VARCHAR(255) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_mapping_source FOREIGN KEY (source_entity_id) REFERENCES source_entity(source_entity_id),
  CONSTRAINT fk_mapping_canonical FOREIGN KEY (canonical_entity_id) REFERENCES canonical_entity(canonical_entity_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS relation_fact (
  relation_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  source_entity_id CHAR(36) CHARACTER SET ascii NOT NULL,
  relation_type VARCHAR(128) NOT NULL,
  target_entity_id CHAR(36) CHARACTER SET ascii NOT NULL,
  valid_from DATETIME(6) NULL,
  valid_to DATETIME(6) NULL,
  conflict_status ENUM('NONE','POTENTIAL','CONFIRMED') NOT NULL DEFAULT 'NONE',
  lifecycle ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_relation_identity (source_entity_id, relation_type, target_entity_id, valid_from),
  CONSTRAINT fk_relation_source FOREIGN KEY (source_entity_id) REFERENCES canonical_entity(canonical_entity_id),
  CONSTRAINT fk_relation_target FOREIGN KEY (target_entity_id) REFERENCES canonical_entity(canonical_entity_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS relation_evidence_support (
  support_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  relation_id CHAR(36) CHARACTER SET ascii NOT NULL,
  origin ENUM('llm_triple','wiki_keyword','alias_derived') NOT NULL,
  evidence_id CHAR(36) CHARACTER SET ascii NOT NULL,
  summary_id CHAR(36) CHARACTER SET ascii NULL,
  wiki_id CHAR(36) CHARACTER SET ascii NULL,
  wiki_version BIGINT UNSIGNED NULL,
  confidence DECIMAL(6,5) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_relation_support_active (relation_id, active),
  CONSTRAINT fk_support_relation FOREIGN KEY (relation_id) REFERENCES relation_fact(relation_id),
  CONSTRAINT fk_support_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS knowledge_event (
  event_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  event_type VARCHAR(128) NOT NULL,
  subject_entity_id CHAR(36) CHARACTER SET ascii NULL,
  object_entity_id CHAR(36) CHARACTER SET ascii NULL,
  occurred_at DATETIME(6) NULL,
  valid_from DATETIME(6) NULL,
  valid_to DATETIME(6) NULL,
  payload_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS search_record (
  search_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  query_text TEXT NOT NULL,
  filters_json JSON NULL,
  mode VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS search_result (
  search_id CHAR(36) CHARACTER SET ascii NOT NULL,
  rank_no INT UNSIGNED NOT NULL,
  result_type ENUM('RAW','SUMMARY','WIKI','GRAPH') NOT NULL,
  result_id VARCHAR(255) CHARACTER SET ascii NOT NULL,
  score DOUBLE NOT NULL,
  evidence_ids JSON NOT NULL,
  effective BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (search_id, rank_no),
  KEY idx_search_result_effective (effective),
  CONSTRAINT fk_search_result_record FOREIGN KEY (search_id) REFERENCES search_record(search_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS effective_version_hit (
  search_id CHAR(36) CHARACTER SET ascii NOT NULL,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (search_id, version_id),
  KEY idx_effective_version_hit_version (version_id, created_at),
  CONSTRAINT fk_effective_hit_search FOREIGN KEY (search_id) REFERENCES search_record(search_id),
  CONSTRAINT fk_effective_hit_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS package_record (
  package_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  object_key VARCHAR(2048) NOT NULL,
  manifest_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS rule_version (
  rule_id VARCHAR(128) CHARACTER SET ascii NOT NULL,
  version_no BIGINT UNSIGNED NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  config_json JSON NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (rule_id, version_no)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS enterprise_user (
  user_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  password_hash VARCHAR(255) CHARACTER SET ascii NOT NULL,
  role ENUM('ADMIN','USER') NOT NULL DEFAULT 'USER',
  status ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  last_login_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_enterprise_user_username (username)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS enterprise_user_session (
  session_hash CHAR(64) CHARACTER SET ascii PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  last_seen_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_enterprise_session_user (user_id),
  KEY idx_enterprise_session_expiry (expires_at),
  CONSTRAINT fk_enterprise_session_user FOREIGN KEY (user_id) REFERENCES enterprise_user(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS enterprise_setting (
  setting_key VARCHAR(128) CHARACTER SET ascii PRIMARY KEY,
  setting_value VARCHAR(512) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ingestion_batch (
  batch_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  created_by CHAR(36) CHARACTER SET ascii NULL,
  created_by_username VARCHAR(64) NOT NULL,
  status ENUM('STAGED','QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED') NOT NULL DEFAULT 'STAGED',
  source_count INT UNSIGNED NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  started_at TIMESTAMP(6) NULL,
  finished_at TIMESTAMP(6) NULL,
  KEY idx_ingestion_batch_created (created_at),
  KEY idx_ingestion_batch_actor (created_by, created_at),
  KEY idx_ingestion_batch_status (status, updated_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ingestion_file (
  file_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL,
  relative_path VARCHAR(1024) NOT NULL,
  original_filename VARCHAR(512) NOT NULL,
  staging_path VARCHAR(2048) NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
  sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
  logical_name VARCHAR(512) NOT NULL,
  source_department VARCHAR(255) NOT NULL DEFAULT '',
  material_category VARCHAR(255) NOT NULL DEFAULT '',
  company VARCHAR(255) NOT NULL DEFAULT '',
  document_type VARCHAR(128) NOT NULL DEFAULT '',
  document_date DATE NULL,
  manual_tags JSON NULL,
  status ENUM('STAGED','QUEUED','RUNNING','SUCCEEDED','FAILED') NOT NULL DEFAULT 'STAGED',
  progress_stage VARCHAR(64) NOT NULL DEFAULT 'STAGED',
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  document_id CHAR(36) CHARACTER SET ascii NULL,
  version_id CHAR(36) CHARACTER SET ascii NULL,
  error_code VARCHAR(128) NOT NULL DEFAULT '',
  error_message TEXT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  started_at TIMESTAMP(6) NULL,
  finished_at TIMESTAMP(6) NULL,
  KEY idx_ingestion_file_batch (batch_id, created_at),
  KEY idx_ingestion_file_status (status, updated_at),
  KEY idx_ingestion_file_sha (sha256),
  CONSTRAINT fk_ingestion_file_batch FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_event (
  event_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  actor_user_id CHAR(36) CHARACTER SET ascii NULL,
  actor_username VARCHAR(64) NOT NULL,
  actor_role VARCHAR(32) NOT NULL,
  action VARCHAR(128) CHARACTER SET ascii NOT NULL,
  target_type VARCHAR(64) CHARACTER SET ascii NOT NULL DEFAULT '',
  target_id VARCHAR(255) CHARACTER SET ascii NOT NULL DEFAULT '',
  target_name VARCHAR(512) NOT NULL DEFAULT '',
  outcome ENUM('SUCCESS','FAILURE') NOT NULL DEFAULT 'SUCCESS',
  request_id VARCHAR(64) CHARACTER SET ascii NOT NULL DEFAULT '',
  ip_address VARCHAR(64) CHARACTER SET ascii NOT NULL DEFAULT '',
  user_agent VARCHAR(512) NOT NULL DEFAULT '',
  details_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_audit_created (created_at),
  KEY idx_audit_actor (actor_username, created_at),
  KEY idx_audit_action (action, created_at),
  KEY idx_audit_target (target_type, target_id, created_at)
) ENGINE=InnoDB;
