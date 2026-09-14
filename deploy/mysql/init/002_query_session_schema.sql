CREATE TABLE IF NOT EXISTS query_session (
  session_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii NOT NULL,
  name VARCHAR(255) NOT NULL,
  original_question MEDIUMTEXT NOT NULL,
  filters_json JSON NOT NULL,
  status ENUM('RUNNING','WAITING_USER','ANSWERED','INSUFFICIENT','FAILED') NOT NULL DEFAULT 'RUNNING',
  final_answer MEDIUMTEXT NULL,
  evidence_only BOOLEAN NOT NULL DEFAULT FALSE,
  clarification_question TEXT NULL,
  choices_json JSON NOT NULL,
  citation_evidence_ids JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at TIMESTAMP(6) NULL,
  KEY idx_query_session_user (user_id, created_at),
  KEY idx_query_session_status (status, updated_at),
  KEY idx_query_session_deleted (user_id, deleted_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS query_turn (
  turn_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  session_id CHAR(36) CHARACTER SET ascii NOT NULL,
  role ENUM('USER','ASSISTANT','SYSTEM_EVENT') NOT NULL,
  content MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_query_turn_session (session_id, created_at),
  CONSTRAINT fk_query_turn_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS retrieval_round (
  round_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  session_id CHAR(36) CHARACTER SET ascii NOT NULL,
  round_no INT UNSIGNED NOT NULL,
  input_queries JSON NOT NULL,
  decision ENUM('ANSWER','CONTINUE_SEARCH','ASK_USER') NOT NULL,
  missing_information JSON NOT NULL,
  next_queries JSON NOT NULL,
  started_at TIMESTAMP(6) NOT NULL,
  finished_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_retrieval_round_session_no (session_id, round_no),
  KEY idx_retrieval_round_session (session_id, started_at),
  CONSTRAINT fk_retrieval_round_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS query_evidence (
  session_id CHAR(36) CHARACTER SET ascii NOT NULL,
  evidence_id CHAR(36) CHARACTER SET ascii NOT NULL,
  first_seen_round INT UNSIGNED NOT NULL,
  supporting_query TEXT NOT NULL,
  PRIMARY KEY (session_id, evidence_id),
  KEY idx_query_evidence_evidence (evidence_id),
  CONSTRAINT fk_query_evidence_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS query_feedback (
  feedback_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  session_id CHAR(36) CHARACTER SET ascii NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii NOT NULL,
  rating ENUM('POSITIVE','NEGATIVE','NEUTRAL') NOT NULL,
  category VARCHAR(64) CHARACTER SET ascii NULL,
  comment TEXT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_query_feedback_session (session_id, created_at),
  KEY idx_query_feedback_user (user_id, created_at),
  CONSTRAINT fk_query_feedback_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS query_group (
  group_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at TIMESTAMP(6) NULL,
  KEY idx_query_group_user (user_id, updated_at),
  KEY idx_query_group_deleted (user_id, deleted_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS query_group_member (
  group_id CHAR(36) CHARACTER SET ascii NOT NULL,
  session_id CHAR(36) CHARACTER SET ascii NOT NULL,
  position INT UNSIGNED NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_at TIMESTAMP(6) NULL,
  PRIMARY KEY (group_id, session_id),
  KEY idx_query_group_member_session (session_id, deleted_at),
  CONSTRAINT fk_query_group_member_group FOREIGN KEY (group_id) REFERENCES query_group(group_id) ON DELETE CASCADE,
  CONSTRAINT fk_query_group_member_session FOREIGN KEY (session_id) REFERENCES query_session(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS query_package (
  package_id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii NOT NULL,
  owner_type ENUM('SESSION','GROUP') NOT NULL,
  owner_id CHAR(36) CHARACTER SET ascii NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  status ENUM('AVAILABLE','DELETED','MISSING') NOT NULL DEFAULT 'AVAILABLE',
  deleted_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_query_package_owner (user_id, owner_type, owner_id, created_at)
) ENGINE=InnoDB;
