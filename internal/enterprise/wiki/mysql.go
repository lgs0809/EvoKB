package wiki

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"

	_ "github.com/go-sql-driver/mysql"
)

type MySQLStore struct { db *sql.DB }

func OpenMySQL(ctx context.Context, dsn string) (*MySQLStore, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil { return nil, fmt.Errorf("wiki mysql open: %w", err) }
	db.SetMaxOpenConns(12)
	db.SetMaxIdleConns(4)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil { _ = db.Close(); return nil, fmt.Errorf("wiki mysql ping: %w", err) }
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil { _ = db.Close(); return nil, fmt.Errorf("wiki mysql migrate: %w", err) }
	return &MySQLStore{db: db}, nil
}

func (s *MySQLStore) Close() error { return s.db.Close() }
