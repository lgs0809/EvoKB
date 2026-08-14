package enterprisehttp

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
)

func TestSecurityRequiresBearerTokenExceptHealth(t *testing.T) {
	srv := &Server{Token: "test-token"}
	handler := srv.Handler()

	health := httptest.NewRecorder()
	handler.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/health", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("health status=%d want=%d", health.Code, http.StatusOK)
	}

	unauthorized := httptest.NewRecorder()
	handler.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodGet, "/api/enterprise/documents", nil))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized status=%d want=%d", unauthorized.Code, http.StatusUnauthorized)
	}

	authorizedRequest := httptest.NewRequest(http.MethodGet, "/api/enterprise/documents", nil)
	authorizedRequest.Header.Set("Authorization", "Bearer test-token")
	authorized := httptest.NewRecorder()
	handler.ServeHTTP(authorized, authorizedRequest)
	if authorized.Code != http.StatusServiceUnavailable {
		t.Fatalf("authorized status=%d want=%d", authorized.Code, http.StatusServiceUnavailable)
	}
}

func TestPublicAuthStatusIsNotBlockedBySecurity(t *testing.T) {
	srv := &Server{Token: "test-token"}
	recorder := httptest.NewRecorder()
	srv.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/api/enterprise/auth/status", nil))
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want=%d", recorder.Code, http.StatusServiceUnavailable)
	}
}

func TestAnonymousEnterpriseAPIIsUnauthorizedWithoutLegacyToken(t *testing.T) {
	srv := &Server{}
	recorder := httptest.NewRecorder()
	srv.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/api/enterprise/capabilities", nil))
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want=%d", recorder.Code, http.StatusUnauthorized)
	}
}

func TestUserPrincipalCannotAccessAdminCapability(t *testing.T) {
	srv := &Server{}
	request := httptest.NewRequest(http.MethodGet, "/api/enterprise/capabilities", nil)
	request = withPrincipal(request, enterpriseauth.User{UserID: "user-1", Role: enterpriseauth.RoleUser, Status: "ACTIVE"})
	recorder := httptest.NewRecorder()
	srv.handleCapabilities(recorder, request)
	if recorder.Code != http.StatusForbidden {
		t.Fatalf("status=%d want=%d", recorder.Code, http.StatusForbidden)
	}
}

func TestLegacyServiceTokenGetsAdminPrincipal(t *testing.T) {
	srv := &Server{Token: "test-token"}
	request := httptest.NewRequest(http.MethodGet, "/api/enterprise/capabilities", nil)
	request.Header.Set("Authorization", "Bearer test-token")
	recorder := httptest.NewRecorder()
	srv.Handler().ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status=%d want=%d body=%s", recorder.Code, http.StatusOK, recorder.Body.String())
	}
}

func TestHealthRejectsUnsupportedMethod(t *testing.T) {
	srv := &Server{}
	recorder := httptest.NewRecorder()
	srv.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/health", nil))
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status=%d want=%d", recorder.Code, http.StatusMethodNotAllowed)
	}
}

func TestDecodeJSONRejectsUnknownFields(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"known":"ok","unexpected":true}`))
	var body struct {
		Known string `json:"known"`
	}
	if err := decodeJSON(recorder, request, &body); err == nil {
		t.Fatal("expected unknown field to be rejected")
	}
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want=%d", recorder.Code, http.StatusBadRequest)
	}
}

func TestDecodeJSONEnforcesBodyLimit(t *testing.T) {
	recorder := httptest.NewRecorder()
	payload := append([]byte(`{"value":"`), bytes.Repeat([]byte("x"), (1<<20)+1)...)
	payload = append(payload, []byte(`"}`)...)
	request := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(payload))
	var body struct {
		Value string `json:"value"`
	}
	if err := decodeJSON(recorder, request, &body); err == nil {
		t.Fatal("expected oversized JSON body to be rejected")
	}
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want=%d", recorder.Code, http.StatusBadRequest)
	}
}

func TestStaticServesAssetsAndFallsBackToSPA(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "index.html"), []byte("<html>enterprise-spa</html>"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "assets"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "assets", "app.js"), []byte("console.log('ok')"), 0o600); err != nil {
		t.Fatal(err)
	}

	srv := &Server{WebDir: root}
	handler := srv.Handler()

	asset := httptest.NewRecorder()
	handler.ServeHTTP(asset, httptest.NewRequest(http.MethodGet, "/assets/app.js", nil))
	if asset.Code != http.StatusOK || !strings.Contains(asset.Body.String(), "console.log") {
		t.Fatalf("asset status=%d body=%q", asset.Code, asset.Body.String())
	}

	spa := httptest.NewRecorder()
	handler.ServeHTTP(spa, httptest.NewRequest(http.MethodGet, "/enterprise/wiki/example", nil))
	if spa.Code != http.StatusOK || !strings.Contains(spa.Body.String(), "enterprise-spa") {
		t.Fatalf("spa status=%d body=%q", spa.Code, spa.Body.String())
	}
}
