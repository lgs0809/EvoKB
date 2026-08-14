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
