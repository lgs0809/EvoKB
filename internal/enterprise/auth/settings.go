package auth

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

const settingUserUploadEnabled = "user_upload_enabled"

type AccessPolicy struct {
	UserUploadEnabled bool `json:"user_upload_enabled"`
}

func (s *Store) AccessPolicy(ctx context.Context) (AccessPolicy, error) {
	var value string
	err := s.db.QueryRowContext(ctx, `SELECT setting_value FROM enterprise_setting WHERE setting_key=?`, settingUserUploadEnabled).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return AccessPolicy{UserUploadEnabled: false}, nil
	}
	if err != nil {
		return AccessPolicy{}, fmt.Errorf("auth access policy: %w", err)
	}
	value = strings.TrimSpace(value)
	return AccessPolicy{UserUploadEnabled: strings.EqualFold(value, "true") || value == "1"}, nil
}

func (s *Store) SetUserUploadEnabled(ctx context.Context, enabled bool) (AccessPolicy, error) {
	value := "false"
	if enabled {
		value = "true"
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO enterprise_setting(setting_key,setting_value) VALUES(?,?)
ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=CURRENT_TIMESTAMP(6)`, settingUserUploadEnabled, value)
	if err != nil {
		return AccessPolicy{}, fmt.Errorf("auth update access policy: %w", err)
	}
	return AccessPolicy{UserUploadEnabled: enabled}, nil
}
