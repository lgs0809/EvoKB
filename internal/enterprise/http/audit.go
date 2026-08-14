package enterprisehttp

import (
	"net"
	"net/http"
	"strings"

	"github.com/google/uuid"
	enterpriseaudit "github.com/xoai/sage-wiki/internal/enterprise/audit"
)

func (s *Server) handleAudit(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Audit == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "audit log unavailable")
		}
		return
	}
	if !requireAdmin(w, r) {
		return
	}
	items, total, err := s.Audit.List(r.Context(), enterpriseaudit.ListOptions{
		Page: queryInt(r, "page", 1), PageSize: queryInt(r, "page_size", 30), Actor: r.URL.Query().Get("actor"), Action: r.URL.Query().Get("action"), Outcome: r.URL.Query().Get("outcome"), Query: r.URL.Query().Get("q"),
	})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if items == nil {
		items = []enterpriseaudit.Event{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"events": items, "total": total, "page": queryInt(r, "page", 1), "page_size": queryInt(r, "page_size", 30)})
}

func (s *Server) auditRequest(r *http.Request, action, targetType, targetID, targetName, outcome string, details map[string]any) {
	if s == nil || s.Audit == nil || r == nil {
		return
	}
	p, _ := principalFromRequest(r)
	actorID, actorName, actorRole := p.User.UserID, p.User.Username, string(p.User.Role)
	if actorName == "" {
		actorName = "anonymous"
		actorRole = "ANONYMOUS"
	}
	s.auditIdentity(r, actorID, actorName, actorRole, action, targetType, targetID, targetName, outcome, details)
}

func (s *Server) auditIdentity(r *http.Request, actorID, actorName, actorRole, action, targetType, targetID, targetName, outcome string, details map[string]any) {
	if s == nil || s.Audit == nil || r == nil {
		return
	}
	if strings.TrimSpace(actorName) == "" {
		actorName = "anonymous"
	}
	if strings.TrimSpace(actorRole) == "" {
		actorRole = "ANONYMOUS"
	}
	requestID := strings.TrimSpace(r.Header.Get("X-Request-ID"))
	if requestID == "" {
		requestID = uuid.NewString()
	}
	ip := clientIP(r, s.TrustProxyHeaders)
	_ = s.Audit.Record(r.Context(), enterpriseaudit.Record{ActorUserID: actorID, ActorUsername: actorName, ActorRole: actorRole, Action: action, TargetType: targetType, TargetID: targetID, TargetName: targetName, Outcome: outcome, RequestID: requestID, IPAddress: ip, UserAgent: r.UserAgent(), Details: details})
}

func clientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if value := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); value != "" {
			if i := strings.IndexByte(value, ','); i >= 0 {
				value = value[:i]
			}
			return strings.TrimSpace(value)
		}
		if value := strings.TrimSpace(r.Header.Get("X-Real-IP")); value != "" {
			return value
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}
