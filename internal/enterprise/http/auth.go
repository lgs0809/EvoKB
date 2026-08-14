package enterprisehttp

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
)

const sessionCookieName = "ekb_session"

type principalKey struct{}

type principal struct {
	User enterpriseauth.User
}

func (s *Server) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Auth == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "authentication unavailable")
		}
		return
	}
	status, err := s.Auth.Status(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, status)
}

func (s *Server) handleAuthSetup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Auth == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "authentication unavailable")
		}
		return
	}
	var req enterpriseauth.CreateUserRequest
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	req.Role = enterpriseauth.RoleAdmin
	if _, err := s.Auth.SetupAdmin(r.Context(), req); err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, enterpriseauth.ErrSetupComplete) {
			status = http.StatusConflict
		}
		writeError(w, status, err.Error())
		return
	}
	login, err := s.Auth.Login(r.Context(), req.Username, req.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "admin created but login failed")
		return
	}
	s.setSessionCookie(w, login.Token, login.ExpiresAt)
	s.auditIdentity(r, login.User.UserID, login.User.Username, string(login.User.Role), "auth.setup", "user", login.User.UserID, login.User.Username, "SUCCESS", nil)
	writeJSON(w, http.StatusCreated, map[string]any{"user": login.User})
}

func (s *Server) handleAuthLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Auth == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "authentication unavailable")
		}
		return
	}
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	result, err := s.Auth.Login(r.Context(), req.Username, req.Password)
	if err != nil {
		if errors.Is(err, enterpriseauth.ErrInvalidCredentials) {
			s.auditIdentity(r, "", strings.TrimSpace(req.Username), "UNKNOWN", "auth.login", "user", "", strings.TrimSpace(req.Username), "FAILURE", map[string]any{"reason": "invalid_credentials"})
			writeError(w, http.StatusUnauthorized, "invalid username or password")
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.setSessionCookie(w, result.Token, result.ExpiresAt)
	s.auditIdentity(r, result.User.UserID, result.User.Username, string(result.User.Role), "auth.login", "user", result.User.UserID, result.User.Username, "SUCCESS", nil)
	writeJSON(w, http.StatusOK, map[string]any{"user": result.User})
}

func (s *Server) handleAuthMe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}
	p, ok := principalFromRequest(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"user": p.User})
}

func (s *Server) handleAuthLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w)
		return
	}
	if s.Auth != nil {
		if cookie, err := r.Cookie(sessionCookieName); err == nil {
			_ = s.Auth.Logout(r.Context(), cookie.Value)
		}
	}
	s.auditRequest(r, "auth.logout", "user", "", "", "SUCCESS", nil)
	s.clearSessionCookie(w)
	writeJSON(w, http.StatusOK, map[string]any{"status": "logged_out"})
}

func (s *Server) handleAuthPassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Auth == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "authentication unavailable")
		}
		return
	}
	p, ok := principalFromRequest(r)
	if !ok || p.User.UserID == "legacy-service-token" {
		writeError(w, http.StatusUnauthorized, "user session required")
		return
	}
	var req struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	if err := s.Auth.ChangePassword(r.Context(), p.User.UserID, req.CurrentPassword, req.NewPassword); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "auth.password.change", "user", p.User.UserID, p.User.Username, "SUCCESS", nil)
	s.clearSessionCookie(w)
	writeJSON(w, http.StatusOK, map[string]any{"status": "password_changed", "login_required": true})
}

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request) {
	if s.Auth == nil {
		writeError(w, http.StatusServiceUnavailable, "authentication unavailable")
		return
	}
	if !requireAdmin(w, r) {
		return
	}
	switch r.Method {
	case http.MethodGet:
		users, err := s.Auth.ListUsers(r.Context())
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		if users == nil {
			users = []enterpriseauth.User{}
		}
		writeJSON(w, http.StatusOK, map[string]any{"users": users, "total": len(users)})
	case http.MethodPost:
		var req enterpriseauth.CreateUserRequest
		if err := decodeJSON(w, r, &req); err != nil {
			return
		}
		user, err := s.Auth.CreateUser(r.Context(), req)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		s.auditRequest(r, "user.create", "user", user.UserID, user.Username, "SUCCESS", map[string]any{"role": user.Role})
		writeJSON(w, http.StatusCreated, map[string]any{"user": user})
	default:
		methodNotAllowed(w)
	}
}

func (s *Server) handleUserUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPatch || s.Auth == nil {
		if r.Method != http.MethodPatch {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "authentication unavailable")
		}
		return
	}
	if !requireAdmin(w, r) {
		return
	}
	userID := strings.TrimSpace(r.URL.Query().Get("id"))
	var req enterpriseauth.UpdateUserRequest
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	user, err := s.Auth.UpdateUser(r.Context(), userID, req)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "user.update", "user", user.UserID, user.Username, "SUCCESS", map[string]any{"role": user.Role, "status": user.Status})
	writeJSON(w, http.StatusOK, map[string]any{"user": user})
}

func (s *Server) handleUserPasswordReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Auth == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "authentication unavailable")
		}
		return
	}
	if !requireAdmin(w, r) {
		return
	}
	var req struct {
		UserID      string `json:"user_id"`
		NewPassword string `json:"new_password"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	if err := s.Auth.ResetPassword(r.Context(), req.UserID, req.NewPassword); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "user.password.reset", "user", req.UserID, req.UserID, "SUCCESS", nil)
	writeJSON(w, http.StatusOK, map[string]any{"status": "password_reset"})
}

func (s *Server) setSessionCookie(w http.ResponseWriter, token string, expiresAt time.Time) {
	maxAge := int(time.Until(expiresAt).Seconds())
	if maxAge < 1 {
		maxAge = 1
	}
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    token,
		Path:     "/",
		Expires:  expiresAt,
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   s.SessionCookieSecure,
		SameSite: http.SameSiteLaxMode,
	})
}

func (s *Server) clearSessionCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		Expires:  time.Unix(1, 0),
		HttpOnly: true,
		Secure:   s.SessionCookieSecure,
		SameSite: http.SameSiteLaxMode,
	})
}

func principalFromRequest(r *http.Request) (principal, bool) {
	value, ok := r.Context().Value(principalKey{}).(principal)
	return value, ok
}

func requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	p, ok := principalFromRequest(r)
	if !ok || p.User.Role != enterpriseauth.RoleAdmin {
		writeError(w, http.StatusForbidden, "admin role required")
		return false
	}
	return true
}

func withPrincipal(r *http.Request, user enterpriseauth.User) *http.Request {
	ctx := context.WithValue(r.Context(), principalKey{}, principal{User: user})
	return r.WithContext(ctx)
}
