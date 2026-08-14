package schema

import (
	"context"
	"database/sql"
	"fmt"

	_ "github.com/go-sql-driver/mysql"
)

type mysqlMigration struct {
	Version int
	Name    string
	SQL     string
}

var mysqlMigrations = []mysqlMigration{
	{
		Version: 2,
		Name:    "wiki_claim_fact",
		SQL: `CREATE TABLE IF NOT EXISTS wiki_claim_fact (
  claim_id CHAR(36) CHARACTER SET ascii NOT NULL,
  fact_id CHAR(36) CHARACTER SET ascii NOT NULL,
  PRIMARY KEY (claim_id, fact_id),
  CONSTRAINT fk_wiki_claim_fact_claim FOREIGN KEY (claim_id) REFERENCES wiki_claim(claim_id),
  CONSTRAINT fk_wiki_claim_fact_fact FOREIGN KEY (fact_id) REFERENCES summary_fact(fact_id)
) ENGINE=InnoDB`,
	},
	{
		Version: 3,
		Name:    "effective_version_hit",
		SQL: `CREATE TABLE IF NOT EXISTS effective_version_hit (
  search_id CHAR(36) CHARACTER SET ascii NOT NULL,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (search_id, version_id),
  KEY idx_effective_version_hit_version (version_id, created_at),
  CONSTRAINT fk_effective_hit_search FOREIGN KEY (search_id) REFERENCES search_record(search_id),
  CONSTRAINT fk_effective_hit_version FOREIGN KEY (version_id) REFERENCES document_version(version_id)
) ENGINE=InnoDB`,
	},
	{
		Version: 4,
		Name:    "enterprise_user",
		SQL: `CREATE TABLE IF NOT EXISTS enterprise_user (
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
) ENGINE=InnoDB`,
	},
	{
		Version: 5,
		Name:    "enterprise_user_session",
		SQL: `CREATE TABLE IF NOT EXISTS enterprise_user_session (
  session_hash CHAR(64) CHARACTER SET ascii PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  last_seen_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_enterprise_session_user (user_id),
  KEY idx_enterprise_session_expiry (expires_at),
  CONSTRAINT fk_enterprise_session_user FOREIGN KEY (user_id) REFERENCES enterprise_user(user_id) ON DELETE CASCADE
) ENGINE=InnoDB`,
	},
	{
		Version: 6,
		Name:    "enterprise_setting",
		SQL: `CREATE TABLE IF NOT EXISTS enterprise_setting (
  setting_key VARCHAR(128) CHARACTER SET ascii PRIMARY KEY,
  setting_value VARCHAR(512) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB`,
	},
	{
		Version: 7,
		Name:    "ingestion_batch",
		SQL: `CREATE TABLE IF NOT EXISTS ingestion_batch (
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
) ENGINE=InnoDB`,
	},
	{
		Version: 8,
		Name:    "ingestion_file",
		SQL: `CREATE TABLE IF NOT EXISTS ingestion_file (
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
) ENGINE=InnoDB`,
	},
	{
		Version: 9,
		Name:    "audit_event",
		SQL: `CREATE TABLE IF NOT EXISTS audit_event (
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
) ENGINE=InnoDB`,
	},
	{
		Version: 10,
		Name:    "document_version_sha_index",
		SQL:     `ALTER TABLE document_version ADD KEY idx_document_version_sha (sha256, created_at)`,
	},
}

func ApplyMySQL(ctx context.Context, dsn string) error {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return fmt.Errorf("enterprise schema mysql open: %w", err)
	}
	defer db.Close()
	if err := db.PingContext(ctx); err != nil {
		return fmt.Errorf("enterprise schema mysql ping: %w", err)
	}
	return ApplyMySQLDB(ctx, db)
}

func ApplyMySQLDB(ctx context.Context, db *sql.DB) error {
	if db == nil {
		return fmt.Errorf("enterprise schema mysql db is required")
	}
	conn, err := db.Conn(ctx)
	if err != nil {
		return fmt.Errorf("enterprise schema mysql connection: %w", err)
	}
	defer conn.Close()

	const migrationLock = "enterprise_kb_schema_migration"
	var acquired sql.NullInt64
	if err := conn.QueryRowContext(ctx, `SELECT GET_LOCK(?, 30)`, migrationLock).Scan(&acquired); err != nil {
		return fmt.Errorf("enterprise schema migration lock: %w", err)
	}
	if !acquired.Valid || acquired.Int64 != 1 {
		return fmt.Errorf("enterprise schema migration lock timeout")
	}
	defer func() {
		var released sql.NullInt64
		_ = conn.QueryRowContext(context.Background(), `SELECT RELEASE_LOCK(?)`, migrationLock).Scan(&released)
	}()

	if _, err := conn.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS enterprise_schema_migration (
  version INT NOT NULL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB`); err != nil {
		return fmt.Errorf("enterprise schema migration table: %w", err)
	}
	for _, migration := range mysqlMigrations {
		var count int
		if err := conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM enterprise_schema_migration WHERE version=?`, migration.Version).Scan(&count); err != nil {
			return fmt.Errorf("enterprise schema migration %d check: %w", migration.Version, err)
		}
		if count > 0 {
			continue
		}

		present, err := migrationObjectAlreadyPresent(ctx, conn, migration)
		if err != nil {
			return fmt.Errorf("enterprise schema migration %d inspect: %w", migration.Version, err)
		}
		tx, err := conn.BeginTx(ctx, nil)
		if err != nil {
			return fmt.Errorf("enterprise schema migration %d begin: %w", migration.Version, err)
		}
		if !present {
			if _, err := tx.ExecContext(ctx, migration.SQL); err != nil {
				_ = tx.Rollback()
				return fmt.Errorf("enterprise schema migration %d %s: %w", migration.Version, migration.Name, err)
			}
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO enterprise_schema_migration(version,name) VALUES(?,?)`, migration.Version, migration.Name); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("enterprise schema migration %d record: %w", migration.Version, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("enterprise schema migration %d commit: %w", migration.Version, err)
		}
	}
	return nil
}

func migrationObjectAlreadyPresent(ctx context.Context, conn *sql.Conn, migration mysqlMigration) (bool, error) {
	if migration.Name != "document_version_sha_index" {
		return false, nil
	}
	var count int
	if err := conn.QueryRowContext(ctx, `
SELECT COUNT(*)
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='document_version' AND index_name='idx_document_version_sha'`).Scan(&count); err != nil {
		return false, err
	}
	return count > 0, nil
}
