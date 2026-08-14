package enterprisehttp

import (
	"net/http"

	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
)

func (s *Server) handleAccess(w http.ResponseWriter, r *http.Request) {
	if s.Auth == nil {
		writeError(w, http.StatusServiceUnavailable, "access policy unavailable")
		return
	}
	p, ok := principalFromRequest(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	switch r.Method {
	case http.MethodGet:
		policy, err := s.Auth.AccessPolicy(r.Context())
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"user_upload_enabled": policy.UserUploadEnabled,
			"can_upload":          p.User.Role == enterpriseauth.RoleAdmin || policy.UserUploadEnabled,
		})
	case http.MethodPatch:
		if !requireAdmin(w, r) {
			return
		}
		var req struct {
			UserUploadEnabled bool `json:"user_upload_enabled"`
		}
		if err := decodeJSON(w, r, &req); err != nil {
			return
		}
		policy, err := s.Auth.SetUserUploadEnabled(r.Context(), req.UserUploadEnabled)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		s.auditRequest(r, "access.user_upload.update", "setting", "user_upload_enabled", "USER upload permission", "SUCCESS", map[string]any{"enabled": policy.UserUploadEnabled})
		writeJSON(w, http.StatusOK, map[string]any{
			"user_upload_enabled": policy.UserUploadEnabled,
			"can_upload":          true,
		})
	default:
		methodNotAllowed(w)
	}
}

func (s *Server) requireUploadPermission(w http.ResponseWriter, r *http.Request) bool {
	p, ok := principalFromRequest(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	if p.User.Role == enterpriseauth.RoleAdmin {
		return true
	}
	if s.Auth == nil {
		writeError(w, http.StatusForbidden, "document upload is restricted to administrators")
		return false
	}
	policy, err := s.Auth.AccessPolicy(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return false
	}
	if !policy.UserUploadEnabled {
		writeError(w, http.StatusForbidden, "document upload is disabled for USER accounts")
		return false
	}
	return true
}
