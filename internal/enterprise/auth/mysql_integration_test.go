package auth

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

func TestAuthLifecycleIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv("EKB_MYSQL_INTEGRATION_DSN"))
	if dsn == "" {
		t.Skip("EKB_MYSQL_INTEGRATION_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	store, err := OpenMySQL(ctx, dsn, time.Hour)
	if err != nil {
		t.Fatalf("open auth store: %v", err)
	}
	defer store.Close()

	status, err := store.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if !status.SetupRequired {
		t.Skip("integration database already contains enterprise users")
	}
	defer func() {
		_, _ = store.db.ExecContext(context.Background(), `DELETE ses FROM enterprise_user_session ses JOIN enterprise_user u ON u.user_id=ses.user_id WHERE u.username LIKE 'auth-it-%'`)
		_, _ = store.db.ExecContext(context.Background(), `DELETE FROM enterprise_user WHERE username LIKE 'auth-it-%'`)
		_, _ = store.db.ExecContext(context.Background(), `DELETE FROM enterprise_setting WHERE setting_key='user_upload_enabled'`)
	}()

	policy, err := store.AccessPolicy(ctx)
	if err != nil || policy.UserUploadEnabled {
		t.Fatalf("default access policy=%+v err=%v", policy, err)
	}
	policy, err = store.SetUserUploadEnabled(ctx, true)
	if err != nil || !policy.UserUploadEnabled {
		t.Fatalf("enable user upload policy=%+v err=%v", policy, err)
	}
	policy, err = store.AccessPolicy(ctx)
	if err != nil || !policy.UserUploadEnabled {
		t.Fatalf("persisted user upload policy=%+v err=%v", policy, err)
	}
	if _, err := store.SetUserUploadEnabled(ctx, false); err != nil {
		t.Fatalf("disable user upload: %v", err)
	}

	adminSecret := strings.Repeat("a", 12)
	userSecret := strings.Repeat("u", 12)
	nextSecret := strings.Repeat("n", 12)
	resetSecret := strings.Repeat("r", 12)

	admin, err := store.SetupAdmin(ctx, CreateUserRequest{Username: "auth-it-admin", DisplayName: "Auth IT Admin", Password: adminSecret, Role: RoleAdmin})
	if err != nil {
		t.Fatalf("setup admin: %v", err)
	}
	if admin.Role != RoleAdmin {
		t.Fatalf("admin role=%s", admin.Role)
	}
	if _, err := store.SetupAdmin(ctx, CreateUserRequest{Username: "auth-it-second", Password: adminSecret, Role: RoleAdmin}); !errors.Is(err, ErrSetupComplete) {
		t.Fatalf("second setup err=%v want ErrSetupComplete", err)
	}
	if _, err := store.Login(ctx, admin.Username, adminSecret+"wrong"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("invalid login err=%v", err)
	}
	adminLogin, err := store.Login(ctx, admin.Username, adminSecret)
	if err != nil {
		t.Fatalf("admin login: %v", err)
	}
	authenticatedAdmin, err := store.Authenticate(ctx, adminLogin.Token)
	if err != nil || authenticatedAdmin.Role != RoleAdmin {
		t.Fatalf("authenticate admin user=%+v err=%v", authenticatedAdmin, err)
	}

	user, err := store.CreateUser(ctx, CreateUserRequest{Username: "auth-it-user", DisplayName: "Auth IT User", Password: userSecret, Role: RoleUser})
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	roleUser := RoleUser
	if _, err := store.UpdateUser(ctx, admin.UserID, UpdateUserRequest{Role: &roleUser}); err == nil {
		t.Fatal("expected last active admin demotion to be rejected")
	}

	userLogin, err := store.Login(ctx, user.Username, userSecret)
	if err != nil {
		t.Fatalf("user login: %v", err)
	}
	if err := store.ChangePassword(ctx, user.UserID, userSecret, nextSecret); err != nil {
		t.Fatalf("change password: %v", err)
	}
	if _, err := store.Authenticate(ctx, userLogin.Token); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("old session after password change err=%v want unauthorized", err)
	}
	userLogin, err = store.Login(ctx, user.Username, nextSecret)
	if err != nil {
		t.Fatalf("login after password change: %v", err)
	}
	if err := store.ResetPassword(ctx, user.UserID, resetSecret); err != nil {
		t.Fatalf("reset password: %v", err)
	}
	if _, err := store.Authenticate(ctx, userLogin.Token); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("old session after reset err=%v want unauthorized", err)
	}
	userLogin, err = store.Login(ctx, user.Username, resetSecret)
	if err != nil {
		t.Fatalf("login after reset: %v", err)
	}
	disabled := "DISABLED"
	if _, err := store.UpdateUser(ctx, user.UserID, UpdateUserRequest{Status: &disabled}); err != nil {
		t.Fatalf("disable user: %v", err)
	}
	if _, err := store.Authenticate(ctx, userLogin.Token); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("disabled user session err=%v want unauthorized", err)
	}
	if err := store.Logout(ctx, adminLogin.Token); err != nil {
		t.Fatalf("logout admin: %v", err)
	}
	if _, err := store.Authenticate(ctx, adminLogin.Token); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("logged-out admin session err=%v want unauthorized", err)
	}
}
