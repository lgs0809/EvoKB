CREATE TABLE IF NOT EXISTS graph_relation_rejection (
  rejection_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  summary_id CHAR(36) CHARACTER SET ascii NOT NULL,
  candidate_index INT UNSIGNED NOT NULL,
  source_name VARCHAR(1024) NULL,
  source_type VARCHAR(128) NULL,
  relation_type VARCHAR(128) NULL,
  target_name VARCHAR(1024) NULL,
  target_type VARCHAR(128) NULL,
  supporting_fact_ids JSON NOT NULL,
  reason VARCHAR(2048) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_graph_rejection_candidate (summary_id, candidate_index),
  KEY idx_graph_rejection_summary (summary_id, created_at),
  CONSTRAINT fk_graph_rejection_summary FOREIGN KEY (summary_id) REFERENCES summary(summary_id) ON DELETE CASCADE
) ENGINE=InnoDB;
