CREATE TABLE IF NOT EXISTS embedding_rebuild_checkpoint (
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  chunk_id VARCHAR(255) CHARACTER SET ascii NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  evidence_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,
  evidence_ids TEXT NOT NULL,
  strategy_key VARCHAR(128) CHARACTER SET ascii NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (version_id, chunk_id),
  KEY idx_embedding_checkpoint_version (version_id, updated_at)
) ENGINE=InnoDB;
