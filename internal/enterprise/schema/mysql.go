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
	if _, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS enterprise_schema_migration (
  version INT NOT NULL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB`); err != nil {
		return fmt.Errorf("enterprise schema migration table: %w", err)
	}
	for _, migration := range mysqlMigrations {
		var count int
		if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM enterprise_schema_migration WHERE version=?`, migration.Version).Scan(&count); err != nil {
			return fmt.Errorf("enterprise schema migration %d check: %w", migration.Version, err)
		}
		if count > 0 {
			continue
		}
		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return fmt.Errorf("enterprise schema migration %d begin: %w", migration.Version, err)
		}
		if _, err := tx.ExecContext(ctx, migration.SQL); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("enterprise schema migration %d %s: %w", migration.Version, migration.Name, err)
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
