CREATE TABLE IF NOT EXISTS model_configuration (
  configuration_id TINYINT UNSIGNED PRIMARY KEY,
  chat_base_url VARCHAR(2048) NOT NULL,
  chat_api_key TEXT NULL,
  small_model VARCHAR(255) NOT NULL,
  large_model VARCHAR(255) NOT NULL,
  chat_timeout_seconds INT UNSIGNED NOT NULL,
  embedding_base_url VARCHAR(2048) NOT NULL,
  embedding_api_key TEXT NULL,
  embedding_model VARCHAR(255) NOT NULL,
  embedding_dimensions INT UNSIGNED NOT NULL,
  embedding_batch_size INT UNSIGNED NOT NULL,
  embedding_timeout_seconds INT UNSIGNED NOT NULL,
  rerank_base_url VARCHAR(2048) NOT NULL,
  rerank_api_key TEXT NULL,
  rerank_model VARCHAR(255) NOT NULL,
  rerank_timeout_seconds INT UNSIGNED NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
