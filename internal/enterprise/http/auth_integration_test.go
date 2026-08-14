package enterprisehttp

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

func TestAuthHTTPIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv("EKB_MYSQL_INTEGRATION_DSN"))
	if dsn == "" {
		t.Skip("EKB_MYSQL_INTEGRATION_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	store, err := enterpriseauth.OpenMySQL(ctx, dsn, time.Hour)
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

	cleanupDB, err := sql.Open("mysql", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer cleanupDB.Close()
	defer func() {
		_, _ = cleanupDB.ExecContext(context.Background(), `DELETE ses FROM enterprise_user_session ses JOIN enterprise_user u ON u.user_id=ses.user_id WHERE u.username LIKE 'auth-http-it-%'`)
		_, _ = cleanupDB.ExecContext(context.Background(), `DELETE FROM enterprise_user WHERE username LIKE 'auth-http-it-%'`)
		_, _ = cleanupDB.ExecContext(context.Background(), `DELETE FROM enterprise_setting WHERE setting_key='user_upload_enabled'`)
	}()

	srv := httptest.NewServer((&Server{Auth: store, Ingest: &raw.Engine{}}).Handler())
	defer srv.Close()
	adminJar, _ := cookiejar.New(nil)
	adminClient := &http.Client{Jar: adminJar, Timeout: 10 * time.Second}
	userJar, _ := cookiejar.New(nil)
	userClient := &http.Client{Jar: userJar, Timeout: 10 * time.Second}
	adminSecret := strings.Repeat("a", 12)
	userSecret := strings.Repeat("u", 12)

	resp := postJSON(t, adminClient, srv.URL+"/api/enterprise/auth/setup", enterpriseauth.CreateUserRequest{
		Username: "auth-http-it-admin", DisplayName: "HTTP Admin", Password: adminSecret, Role: enterpriseauth.RoleAdmin,
	})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("setup status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	cookies := resp.Cookies()
	if len(cookies) == 0 || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteLaxMode {
		t.Fatalf("session cookie flags=%+v", cookies)
	}
	_ = resp.Body.Close()

	resp = get(t, adminClient, srv.URL+"/api/enterprise/auth/me")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("admin me status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	_ = resp.Body.Close()

	resp = postJSON(t, adminClient, srv.URL+"/api/enterprise/users", enterpriseauth.CreateUserRequest{
		Username: "auth-http-it-user", DisplayName: "HTTP User", Password: userSecret, Role: enterpriseauth.RoleUser,
	})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create user status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	_ = resp.Body.Close()

	resp = postJSON(t, userClient, srv.URL+"/api/enterprise/auth/login", map[string]string{"username": "auth-http-it-user", "password": userSecret})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("user login status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	_ = resp.Body.Close()

	resp = get(t, userClient, srv.URL+"/api/enterprise/auth/me")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("user me status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	_ = resp.Body.Close()
	resp = get(t, userClient, srv.URL+"/api/enterprise/access")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("user access status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	var access struct {
		UserUploadEnabled bool `json:"user_upload_enabled"`
		CanUpload         bool `json:"can_upload"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&access); err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if access.UserUploadEnabled || access.CanUpload {
		t.Fatalf("default user access=%+v", access)
	}
	resp = postEmpty(t, userClient, srv.URL+"/api/enterprise/upload")
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("user upload while disabled status=%d want=%d body=%s", resp.StatusCode, http.StatusForbidden, readBody(resp))
	}
	_ = resp.Body.Close()
	resp = patchJSON(t, userClient, srv.URL+"/api/enterprise/access", map[string]bool{"user_upload_enabled": true})
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("user access update status=%d want=%d body=%s", resp.StatusCode, http.StatusForbidden, readBody(resp))
	}
	_ = resp.Body.Close()
	resp = patchJSON(t, adminClient, srv.URL+"/api/enterprise/access", map[string]bool{"user_upload_enabled": true})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("admin access update status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	_ = resp.Body.Close()
	resp = get(t, userClient, srv.URL+"/api/enterprise/access")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("user access after enable status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	if err := json.NewDecoder(resp.Body).Decode(&access); err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if !access.UserUploadEnabled || !access.CanUpload {
		t.Fatalf("enabled user access=%+v", access)
	}
	resp = postEmpty(t, userClient, srv.URL+"/api/enterprise/upload")
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("enabled user should pass upload authorization, status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	_ = resp.Body.Close()
	resp = get(t, userClient, srv.URL+"/api/enterprise/capabilities")
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("user capabilities status=%d want=%d body=%s", resp.StatusCode, http.StatusForbidden, readBody(resp))
	}
	_ = resp.Body.Close()
	resp = get(t, userClient, srv.URL+"/api/enterprise/users")
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("user users status=%d want=%d body=%s", resp.StatusCode, http.StatusForbidden, readBody(resp))
	}
	_ = resp.Body.Close()

	resp = postJSON(t, userClient, srv.URL+"/api/enterprise/auth/logout", map[string]string{})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("logout status=%d body=%s", resp.StatusCode, readBody(resp))
	}
	_ = resp.Body.Close()
	resp = get(t, userClient, srv.URL+"/api/enterprise/auth/me")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("me after logout status=%d want=%d", resp.StatusCode, http.StatusUnauthorized)
	}
	_ = resp.Body.Close()

	resp = postJSON(t, adminClient, srv.URL+"/api/enterprise/auth/setup", enterpriseauth.CreateUserRequest{
		Username: "auth-http-it-second", Password: adminSecret, Role: enterpriseauth.RoleAdmin,
	})
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("second setup status=%d want=%d body=%s", resp.StatusCode, http.StatusConflict, readBody(resp))
	}
	_ = resp.Body.Close()
}

func postJSON(t *testing.T, client *http.Client, url string, value any) *http.Response {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func get(t *testing.T, client *http.Client, url string) *http.Response {
	t.Helper()
	resp, err := client.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func patchJSON(t *testing.T, client *http.Client, url string, value any) *http.Response {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodPatch, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func postEmpty(t *testing.T, client *http.Client, url string) *http.Response {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func readBody(resp *http.Response) string {
	if resp == nil || resp.Body == nil {
		return ""
	}
	var payload any
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return err.Error()
	}
	encoded, _ := json.Marshal(payload)
	return string(encoded)
}
