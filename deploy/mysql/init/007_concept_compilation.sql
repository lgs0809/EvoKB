CREATE TABLE IF NOT EXISTS concept_candidate (
  concept_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  summary_id CHAR(36) CHARACTER SET ascii NOT NULL,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  preferred_label VARCHAR(1024) NOT NULL,
  concept_type_hint VARCHAR(128) NOT NULL DEFAULT 'OTHER',
  description TEXT NULL,
  lifecycle ENUM('ACTIVE','STALE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  model VARCHAR(255) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY idx_concept_summary (summary_id, lifecycle),
  KEY idx_concept_version (version_id, lifecycle),
  CONSTRAINT fk_concept_summary FOREIGN KEY (summary_id) REFERENCES summary(summary_id),
  CONSTRAINT fk_concept_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS concept_alias (
  alias_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  concept_id CHAR(36) CHARACTER SET ascii NOT NULL,
  alias_text VARCHAR(1024) NOT NULL,
  alias_key CHAR(64) CHARACTER SET ascii NOT NULL,
  UNIQUE KEY uq_concept_alias_key (concept_id, alias_key),
  CONSTRAINT fk_concept_alias_concept FOREIGN KEY (concept_id) REFERENCES concept_candidate(concept_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS concept_fact_support (
  concept_id CHAR(36) CHARACTER SET ascii NOT NULL,
  fact_id CHAR(36) CHARACTER SET ascii NOT NULL,
  PRIMARY KEY (concept_id, fact_id),
  CONSTRAINT fk_concept_fact_concept FOREIGN KEY (concept_id) REFERENCES concept_candidate(concept_id) ON DELETE CASCADE,
  CONSTRAINT fk_concept_fact_fact FOREIGN KEY (fact_id) REFERENCES summary_fact(fact_id)
) ENGINE=InnoDB;
