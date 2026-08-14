package audit

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"

	_ "github.com/go-sql-driver/mysql"
)

type Event struct {
	EventID       string          `json:"event_id"`
	ActorUserID   string          `json:"actor_user_id,omitempty"`
	ActorUsername string          `json:"actor_username"`
	ActorRole     string          `json:"actor_role"`
	Action        string          `json:"action"`
	TargetType    string          `json:"target_type,omitempty"`
	TargetID      string          `json:"target_id,omitempty"`
	TargetName    string          `json:"target_name,omitempty"`
	Outcome       string          `json:"outcome"`
	RequestID     string          `json:"request_id,omitempty"`
	IPAddress     string          `json:"ip_address,omitempty"`
	UserAgent     string          `json:"user_agent,omitempty"`
	Details       json.RawMessage `json:"details,omitempty"`
	CreatedAt     time.Time       `json:"created_at"`
}

type Record struct {
	ActorUserID   string
	ActorUsername string
	ActorRole     string
	Action        string
	TargetType    string
	TargetID      string
	TargetName    string
	Outcome       string
	RequestID     string
	IPAddress     string
	UserAgent     string
	Details       map[string]any
}

type ListOptions struct {
	Page     int
	PageSize int
	Actor    string
	Action   string
	Outcome  string
	Query    string
}

type Store struct{ db *sql.DB }

func OpenMySQL(ctx context.Context, dsn string) (*Store, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("audit mysql open: %w", err)
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("audit mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("audit mysql migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) Record(ctx context.Context, input Record) error {
	if s == nil {
		return nil
	}
	if strings.TrimSpace(input.Action) == "" {
		return fmt.Errorf("audit action is required")
	}
	outcome := strings.ToUpper(strings.TrimSpace(input.Outcome))
	if outcome == "" {
		outcome = "SUCCESS"
	}
	if outcome != "SUCCESS" && outcome != "FAILURE" {
		return fmt.Errorf("invalid audit outcome")
	}
	var details any
	if len(input.Details) > 0 {
		b, err := json.Marshal(input.Details)
		if err != nil {
			return err
		}
		details = b
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO audit_event(event_id,actor_user_id,actor_username,actor_role,action,target_type,target_id,target_name,outcome,request_id,ip_address,user_agent,details_json)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`, uuid.NewString(), nullable(input.ActorUserID), clean(input.ActorUsername, 64), clean(input.ActorRole, 32), clean(input.Action, 128), clean(input.TargetType, 64), clean(input.TargetID, 255), clean(input.TargetName, 512), outcome, clean(input.RequestID, 64), clean(input.IPAddress, 64), clean(input.UserAgent, 512), details)
	if err != nil {
		return fmt.Errorf("audit insert: %w", err)
	}
	return nil
}

func (s *Store) List(ctx context.Context, opts ListOptions) ([]Event, int, error) {
	page, pageSize := opts.Page, opts.PageSize
	if page < 1 {
		page = 1
	}
	if pageSize < 1 {
		pageSize = 30
	}
	if pageSize > 100 {
		pageSize = 100
	}
	where := []string{"1=1"}
	args := []any{}
	if v := strings.TrimSpace(opts.Actor); v != "" {
		where = append(where, "actor_username=?")
		args = append(args, v)
	}
	if v := strings.TrimSpace(opts.Action); v != "" {
		where = append(where, "action=?")
		args = append(args, v)
	}
	if v := strings.ToUpper(strings.TrimSpace(opts.Outcome)); v != "" && v != "ALL" {
		where = append(where, "outcome=?")
		args = append(args, v)
	}
	if q := strings.TrimSpace(opts.Query); q != "" {
		like := "%" + q + "%"
		where = append(where, "(actor_username LIKE ? OR action LIKE ? OR target_name LIKE ? OR target_id LIKE ?)")
		args = append(args, like, like, like, like)
	}
	whereSQL := strings.Join(where, " AND ")
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM audit_event WHERE `+whereSQL, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	query := `SELECT event_id,COALESCE(actor_user_id,''),actor_username,actor_role,action,target_type,target_id,target_name,outcome,request_id,ip_address,user_agent,details_json,created_at FROM audit_event WHERE ` + whereSQL + ` ORDER BY created_at DESC,event_id DESC LIMIT ? OFFSET ?`
	qargs := append(append([]any{}, args...), pageSize, (page-1)*pageSize)
	rows, err := s.db.QueryContext(ctx, query, qargs...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []Event
	for rows.Next() {
		var item Event
		var details []byte
		if err := rows.Scan(&item.EventID, &item.ActorUserID, &item.ActorUsername, &item.ActorRole, &item.Action, &item.TargetType, &item.TargetID, &item.TargetName, &item.Outcome, &item.RequestID, &item.IPAddress, &item.UserAgent, &details, &item.CreatedAt); err != nil {
			return nil, 0, err
		}
		item.Details = append(item.Details[:0], details...)
		item.CreatedAt = item.CreatedAt.UTC()
		out = append(out, item)
	}
	return out, total, rows.Err()
}

func clean(value string, max int) string {
	value = strings.TrimSpace(value)
	if len(value) > max {
		return value[:max]
	}
	return value
}
func nullable(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return value
}
