package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"
	"golang.org/x/crypto/bcrypt"

	_ "github.com/go-sql-driver/mysql"
)

var usernamePattern = regexp.MustCompile(`^[A-Za-z0-9._-]{3,64}$`)

var (
	ErrInvalidCredentials = errors.New("invalid username or password")
	ErrUnauthorized       = errors.New("unauthorized")
	ErrSetupComplete      = errors.New("initial setup is already complete")
)

type Store struct {
	db         *sql.DB
	sessionTTL time.Duration
}

func OpenMySQL(ctx context.Context, dsn string, sessionTTL time.Duration) (*Store, error) {
	if sessionTTL <= 0 {
		sessionTTL = 12 * time.Hour
	}
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("auth mysql open: %w", err)
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("auth mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("auth mysql migrate: %w", err)
	}
	return &Store{db: db, sessionTTL: sessionTTL}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) Status(ctx context.Context) (Status, error) {
	var count int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM enterprise_user`).Scan(&count); err != nil {
		return Status{}, fmt.Errorf("auth status: %w", err)
	}
	return Status{SetupRequired: count == 0, Users: count}, nil
}

func (s *Store) SetupAdmin(ctx context.Context, req CreateUserRequest) (User, error) {
	req.Role = RoleAdmin
	if err := validateCreateUser(req); err != nil {
		return User{}, err
	}
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return User{}, fmt.Errorf("auth setup begin: %w", err)
	}
	defer tx.Rollback()
	var count int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM enterprise_user FOR UPDATE`).Scan(&count); err != nil {
		return User{}, fmt.Errorf("auth setup check: %w", err)
	}
	if count != 0 {
		return User{}, ErrSetupComplete
	}
	user, err := createUserTx(ctx, tx, req)
	if err != nil {
		return User{}, err
	}
	if err := tx.Commit(); err != nil {
		return User{}, fmt.Errorf("auth setup commit: %w", err)
	}
	return user, nil
}

func (s *Store) Login(ctx context.Context, username, password string) (LoginResult, error) {
	username = strings.TrimSpace(username)
	var user User
	var hash string
	var lastLogin sql.NullTime
	err := s.db.QueryRowContext(ctx, `
SELECT user_id,username,display_name,password_hash,role,status,last_login_at,created_at,updated_at
FROM enterprise_user
WHERE username=? LIMIT 1`, username).Scan(
		&user.UserID, &user.Username, &user.DisplayName, &hash, &user.Role, &user.Status, &lastLogin, &user.CreatedAt, &user.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return LoginResult{}, ErrInvalidCredentials
	}
	if err != nil {
		return LoginResult{}, fmt.Errorf("auth login lookup: %w", err)
	}
	if user.Status != "ACTIVE" || bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) != nil {
		return LoginResult{}, ErrInvalidCredentials
	}
	token, tokenHash, err := newSessionToken()
	if err != nil {
		return LoginResult{}, err
	}
	expiresAt := time.Now().UTC().Add(s.sessionTTL)
	if _, err := s.db.ExecContext(ctx, `
INSERT INTO enterprise_user_session(session_hash,user_id,expires_at,last_seen_at)
VALUES(?,?,?,CURRENT_TIMESTAMP(6))`, tokenHash, user.UserID, expiresAt); err != nil {
		return LoginResult{}, fmt.Errorf("auth create session: %w", err)
	}
	now := time.Now().UTC()
	_, _ = s.db.ExecContext(ctx, `UPDATE enterprise_user SET last_login_at=? WHERE user_id=?`, now, user.UserID)
	user.LastLoginAt = &now
	user.CreatedAt = user.CreatedAt.UTC()
	user.UpdatedAt = user.UpdatedAt.UTC()
	return LoginResult{User: user, Token: token, ExpiresAt: expiresAt}, nil
}

func (s *Store) Authenticate(ctx context.Context, token string) (User, error) {
	if strings.TrimSpace(token) == "" {
		return User{}, ErrUnauthorized
	}
	hash := hashToken(token)
	var user User
	var lastLogin sql.NullTime
	err := s.db.QueryRowContext(ctx, `
SELECT u.user_id,u.username,u.display_name,u.role,u.status,u.last_login_at,u.created_at,u.updated_at
FROM enterprise_user_session ses
JOIN enterprise_user u ON u.user_id=ses.user_id
WHERE ses.session_hash=? AND ses.expires_at>CURRENT_TIMESTAMP(6) AND u.status='ACTIVE'
LIMIT 1`, hash).Scan(
		&user.UserID, &user.Username, &user.DisplayName, &user.Role, &user.Status, &lastLogin, &user.CreatedAt, &user.UpdatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrUnauthorized
	}
	if err != nil {
		return User{}, fmt.Errorf("auth session lookup: %w", err)
	}
	if lastLogin.Valid {
		value := lastLogin.Time.UTC()
		user.LastLoginAt = &value
	}
	user.CreatedAt = user.CreatedAt.UTC()
	user.UpdatedAt = user.UpdatedAt.UTC()
	_, _ = s.db.ExecContext(ctx, `UPDATE enterprise_user_session SET last_seen_at=CURRENT_TIMESTAMP(6) WHERE session_hash=?`, hash)
	return user, nil
}

func (s *Store) Logout(ctx context.Context, token string) error {
	if strings.TrimSpace(token) == "" {
		return nil
	}
	_, err := s.db.ExecContext(ctx, `DELETE FROM enterprise_user_session WHERE session_hash=?`, hashToken(token))
	if err != nil {
		return fmt.Errorf("auth logout: %w", err)
	}
	return nil
}

func (s *Store) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT user_id,username,display_name,role,status,last_login_at,created_at,updated_at
FROM enterprise_user ORDER BY created_at,user_id`)
	if err != nil {
		return nil, fmt.Errorf("auth list users: %w", err)
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var user User
		var lastLogin sql.NullTime
		if err := rows.Scan(&user.UserID, &user.Username, &user.DisplayName, &user.Role, &user.Status, &lastLogin, &user.CreatedAt, &user.UpdatedAt); err != nil {
			return nil, fmt.Errorf("auth list users scan: %w", err)
		}
		if lastLogin.Valid {
			value := lastLogin.Time.UTC()
			user.LastLoginAt = &value
		}
		user.CreatedAt = user.CreatedAt.UTC()
		user.UpdatedAt = user.UpdatedAt.UTC()
		out = append(out, user)
	}
	return out, rows.Err()
}

func (s *Store) CreateUser(ctx context.Context, req CreateUserRequest) (User, error) {
	if err := validateCreateUser(req); err != nil {
		return User{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return User{}, fmt.Errorf("auth create user begin: %w", err)
	}
	defer tx.Rollback()
	user, err := createUserTx(ctx, tx, req)
	if err != nil {
		return User{}, err
	}
	if err := tx.Commit(); err != nil {
		return User{}, fmt.Errorf("auth create user commit: %w", err)
	}
	return user, nil
}

func (s *Store) UpdateUser(ctx context.Context, userID string, req UpdateUserRequest) (User, error) {
	userID = strings.TrimSpace(userID)
	if userID == "" {
		return User{}, fmt.Errorf("user_id is required")
	}
	if req.Role != nil && *req.Role != RoleAdmin && *req.Role != RoleUser {
		return User{}, fmt.Errorf("unsupported role")
	}
	if req.Status != nil && *req.Status != "ACTIVE" && *req.Status != "DISABLED" {
		return User{}, fmt.Errorf("unsupported status")
	}
	if req.DisplayName != nil && len(strings.TrimSpace(*req.DisplayName)) > 128 {
		return User{}, fmt.Errorf("display_name is too long")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return User{}, err
	}
	defer tx.Rollback()
	var currentRole Role
	var currentStatus string
	if err := tx.QueryRowContext(ctx, `SELECT role,status FROM enterprise_user WHERE user_id=? FOR UPDATE`, userID).Scan(&currentRole, &currentStatus); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return User{}, fmt.Errorf("user not found")
		}
		return User{}, err
	}
	nextRole, nextStatus := currentRole, currentStatus
	if req.Role != nil {
		nextRole = *req.Role
	}
	if req.Status != nil {
		nextStatus = *req.Status
	}
	if currentRole == RoleAdmin && currentStatus == "ACTIVE" && (nextRole != RoleAdmin || nextStatus != "ACTIVE") {
		var admins int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM enterprise_user WHERE role='ADMIN' AND status='ACTIVE'`).Scan(&admins); err != nil {
			return User{}, err
		}
		if admins <= 1 {
			return User{}, fmt.Errorf("cannot disable or demote the last active admin")
		}
	}
	if req.DisplayName != nil {
		if _, err := tx.ExecContext(ctx, `UPDATE enterprise_user SET display_name=? WHERE user_id=?`, strings.TrimSpace(*req.DisplayName), userID); err != nil {
			return User{}, err
		}
	}
	if req.Role != nil {
		if _, err := tx.ExecContext(ctx, `UPDATE enterprise_user SET role=? WHERE user_id=?`, *req.Role, userID); err != nil {
			return User{}, err
		}
	}
	if req.Status != nil {
		if _, err := tx.ExecContext(ctx, `UPDATE enterprise_user SET status=? WHERE user_id=?`, *req.Status, userID); err != nil {
			return User{}, err
		}
		if *req.Status == "DISABLED" {
			if _, err := tx.ExecContext(ctx, `DELETE FROM enterprise_user_session WHERE user_id=?`, userID); err != nil {
				return User{}, err
			}
		}
	}
	if err := tx.Commit(); err != nil {
		return User{}, err
	}
	return s.GetUser(ctx, userID)
}

func (s *Store) ChangePassword(ctx context.Context, userID, currentPassword, newPassword string) error {
	if err := validatePassword(newPassword); err != nil {
		return err
	}
	var currentHash string
	if err := s.db.QueryRowContext(ctx, `SELECT password_hash FROM enterprise_user WHERE user_id=? AND status='ACTIVE'`, userID).Scan(&currentHash); err != nil {
		return ErrUnauthorized
	}
	if bcrypt.CompareHashAndPassword([]byte(currentHash), []byte(currentPassword)) != nil {
		return fmt.Errorf("current password is incorrect")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(newPassword), bcrypt.DefaultCost)
	if err != nil {
		return fmt.Errorf("hash password: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE enterprise_user SET password_hash=? WHERE user_id=?`, string(hash), userID); err != nil {
		return fmt.Errorf("update password: %w", err)
	}
	_, _ = s.db.ExecContext(ctx, `DELETE FROM enterprise_user_session WHERE user_id=?`, userID)
	return nil
}

func (s *Store) ResetPassword(ctx context.Context, userID, newPassword string) error {
	if err := validatePassword(newPassword); err != nil {
		return err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(newPassword), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	result, err := s.db.ExecContext(ctx, `UPDATE enterprise_user SET password_hash=? WHERE user_id=?`, string(hash), userID)
	if err != nil {
		return err
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("user not found")
	}
	_, _ = s.db.ExecContext(ctx, `DELETE FROM enterprise_user_session WHERE user_id=?`, userID)
	return nil
}

func (s *Store) GetUser(ctx context.Context, userID string) (User, error) {
	var user User
	var lastLogin sql.NullTime
	err := s.db.QueryRowContext(ctx, `
SELECT user_id,username,display_name,role,status,last_login_at,created_at,updated_at
FROM enterprise_user WHERE user_id=?`, userID).Scan(
		&user.UserID, &user.Username, &user.DisplayName, &user.Role, &user.Status, &lastLogin, &user.CreatedAt, &user.UpdatedAt,
	)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return User{}, fmt.Errorf("user not found")
		}
		return User{}, err
	}
	if lastLogin.Valid {
		value := lastLogin.Time.UTC()
		user.LastLoginAt = &value
	}
	user.CreatedAt = user.CreatedAt.UTC()
	user.UpdatedAt = user.UpdatedAt.UTC()
	return user, nil
}

func createUserTx(ctx context.Context, tx *sql.Tx, req CreateUserRequest) (User, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return User{}, fmt.Errorf("hash password: %w", err)
	}
	user := User{
		UserID:      newID(),
		Username:    strings.TrimSpace(req.Username),
		DisplayName: strings.TrimSpace(req.DisplayName),
		Role:        req.Role,
		Status:      "ACTIVE",
	}
	if user.DisplayName == "" {
		user.DisplayName = user.Username
	}
	_, err = tx.ExecContext(ctx, `
INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role,status)
VALUES(?,?,?,?,?,'ACTIVE')`, user.UserID, user.Username, user.DisplayName, string(hash), user.Role)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "duplicate") {
			return User{}, fmt.Errorf("username already exists")
		}
		return User{}, fmt.Errorf("auth create user: %w", err)
	}
	if err := tx.QueryRowContext(ctx, `SELECT created_at,updated_at FROM enterprise_user WHERE user_id=?`, user.UserID).Scan(&user.CreatedAt, &user.UpdatedAt); err != nil {
		return User{}, err
	}
	user.CreatedAt = user.CreatedAt.UTC()
	user.UpdatedAt = user.UpdatedAt.UTC()
	return user, nil
}

func validateCreateUser(req CreateUserRequest) error {
	if !usernamePattern.MatchString(strings.TrimSpace(req.Username)) {
		return fmt.Errorf("username must be 3-64 characters using letters, numbers, dot, underscore, or hyphen")
	}
	if req.Role != RoleAdmin && req.Role != RoleUser {
		return fmt.Errorf("role must be ADMIN or USER")
	}
	if len(strings.TrimSpace(req.DisplayName)) > 128 {
		return fmt.Errorf("display_name is too long")
	}
	return validatePassword(req.Password)
}

func validatePassword(password string) error {
	if len(password) < 10 {
		return fmt.Errorf("password must be at least 10 characters")
	}
	if len(password) > 256 {
		return fmt.Errorf("password is too long")
	}
	return nil
}

func newSessionToken() (string, string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", "", fmt.Errorf("generate session token: %w", err)
	}
	token := base64.RawURLEncoding.EncodeToString(buf)
	return token, hashToken(token), nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func newID() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	buf[6] = (buf[6] & 0x0f) | 0x40
	buf[8] = (buf[8] & 0x3f) | 0x80
	hexID := hex.EncodeToString(buf)
	return hexID[:8] + "-" + hexID[8:12] + "-" + hexID[12:16] + "-" + hexID[16:20] + "-" + hexID[20:]
}
