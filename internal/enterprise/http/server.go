package enterprisehttp

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	enterpriseadmin "github.com/xoai/sage-wiki/internal/enterprise/admin"
	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
	enterpriseask "github.com/xoai/sage-wiki/internal/enterprise/ask"
	enterpriseaudit "github.com/xoai/sage-wiki/internal/enterprise/audit"
	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
	enterprisegraph "github.com/xoai/sage-wiki/internal/enterprise/graph"
	enterpriseingest "github.com/xoai/sage-wiki/internal/enterprise/ingest"
	enterprisepromotion "github.com/xoai/sage-wiki/internal/enterprise/promotion"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Server struct {
	Admin                 *enterpriseadmin.MySQLStore
	API                   *enterpriseapi.Service
	Ask                   *enterpriseask.Service
	Audit                 *enterpriseaudit.Store
	Auth                  *enterpriseauth.Store
	Batches               *enterpriseingest.Store
	Promotion             *enterprisepromotion.MySQLStore
	Resolver              *enterpriseevidence.Resolver
	Publisher             *enterpriseevidence.Publisher
	Graph                 *enterprisegraph.Neo4jClient
	Ingest                *raw.Engine
	WebDir                string
	Token                 string
	SessionCookieSecure   bool
	TrustProxyHeaders     bool
	MaxUploadBytes        int64
	MaxBatchUploadBytes   int64
	MaxBatchExpandedBytes int64
	MaxBatchFiles         int
	StagingRoot           string
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/api/enterprise/auth/status", s.handleAuthStatus)
	mux.HandleFunc("/api/enterprise/auth/setup", s.handleAuthSetup)
	mux.HandleFunc("/api/enterprise/auth/login", s.handleAuthLogin)
	mux.HandleFunc("/api/enterprise/auth/me", s.handleAuthMe)
	mux.HandleFunc("/api/enterprise/auth/logout", s.handleAuthLogout)
	mux.HandleFunc("/api/enterprise/auth/password", s.handleAuthPassword)
	mux.HandleFunc("/api/enterprise/access", s.handleAccess)
	mux.HandleFunc("/api/enterprise/users", s.handleUsers)
	mux.HandleFunc("/api/enterprise/user", s.handleUserUpdate)
	mux.HandleFunc("/api/enterprise/user/password/reset", s.handleUserPasswordReset)
	mux.HandleFunc("/api/enterprise/upload", s.handleUpload)
	mux.HandleFunc("/api/enterprise/batches", s.handleBatches)
	mux.HandleFunc("/api/enterprise/batch", s.handleBatch)
	mux.HandleFunc("/api/enterprise/batch/file", s.handleBatchFile)
	mux.HandleFunc("/api/enterprise/batch/start", s.handleBatchStart)
	mux.HandleFunc("/api/enterprise/batch/file/retry", s.handleBatchRetry)
	mux.HandleFunc("/api/enterprise/audit", s.handleAudit)
	mux.HandleFunc("/api/enterprise/overview", s.handleOverview)
	mux.HandleFunc("/api/enterprise/tasks", s.handleTasks)
	mux.HandleFunc("/api/enterprise/evidences", s.handleEvidences)
	mux.HandleFunc("/api/enterprise/facets", s.handleFacets)
	mux.HandleFunc("/api/enterprise/capabilities", s.handleCapabilities)
	mux.HandleFunc("/api/enterprise/documents", s.handleDocuments)
	mux.HandleFunc("/api/enterprise/distill", s.handleDistill)
	mux.HandleFunc("/api/enterprise/wikis", s.handleWikis)
	mux.HandleFunc("/api/enterprise/wiki", s.handleWiki)
	mux.HandleFunc("/api/enterprise/evidence", s.handleEvidence)
	mux.HandleFunc("/api/enterprise/evidence/source", s.handleEvidenceSource)
	mux.HandleFunc("/api/enterprise/evidence/source/file", s.handleEvidenceSourceFile)
	mux.HandleFunc("/api/enterprise/graph", s.handleGraph)
	mux.HandleFunc("/api/enterprise/ask", s.handleAsk)
	mux.HandleFunc("/api/enterprise/search", s.handleSearch)
	mux.HandleFunc("/api/enterprise/package", s.handlePackage)
	mux.HandleFunc("/", s.handleStatic)
	return s.security(mux)
}

func (s *Server) Serve(ctx context.Context, addr string) error {
	if strings.TrimSpace(addr) == "" {
		addr = "127.0.0.1:18080"
	}
	httpServer := &http.Server{
		Addr:              addr,
		Handler:           s.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Minute,
		WriteTimeout:      30 * time.Minute,
		IdleTimeout:       90 * time.Second,
	}
	errCh := make(chan error, 1)
	go func() { errCh <- httpServer.ListenAndServe() }()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return httpServer.Shutdown(shutdownCtx)
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "service": "enterprise-knowledge-base"})
}

func (s *Server) handleOverview(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Admin == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "overview unavailable")
		}
		return
	}
	item, err := s.Admin.Overview(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (s *Server) handleTasks(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Admin == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "tasks unavailable")
		}
		return
	}
	if !requireAdmin(w, r) {
		return
	}
	page, pageSize := queryInt(r, "page", 1), queryInt(r, "page_size", 30)
	items, total, err := s.Admin.ListTasksPage(r.Context(), enterpriseadmin.PageQuery{Page: page, PageSize: pageSize, Query: r.URL.Query().Get("q"), Status: r.URL.Query().Get("status")})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if items == nil {
		items = []enterpriseadmin.CompileTaskView{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"tasks": items, "total": total, "page": page, "page_size": pageSize})
}

func (s *Server) handleEvidences(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Admin == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "evidences unavailable")
		}
		return
	}
	page, pageSize := queryInt(r, "page", 1), queryInt(r, "page_size", 30)
	var active *bool
	if raw := strings.TrimSpace(r.URL.Query().Get("active")); raw != "" {
		value, err := strconv.ParseBool(raw)
		if err != nil {
			writeError(w, http.StatusBadRequest, "active must be true or false")
			return
		}
		active = &value
	}
	items, total, err := s.Admin.ListEvidencePage(r.Context(), enterpriseadmin.PageQuery{Page: page, PageSize: pageSize, Query: r.URL.Query().Get("q"), Active: active})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if items == nil {
		items = []enterpriseadmin.EvidenceListView{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"evidences": items, "total": total, "page": page, "page_size": pageSize})
}

func (s *Server) handleFacets(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Admin == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "metadata facets unavailable")
		}
		return
	}
	facets, err := s.Admin.Facets(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, facets)
}

func (s *Server) handleCapabilities(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}
	if !requireAdmin(w, r) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"auth":            s.Auth != nil,
		"users":           s.Auth != nil,
		"documents":       s.Admin != nil,
		"upload":          s.Ingest != nil,
		"batch_ingestion": s.Batches != nil,
		"tasks":           s.Admin != nil,
		"audit":           s.Audit != nil,
		"evidence":        s.Resolver != nil,
		"source_viewer":   s.Resolver != nil && s.Publisher != nil,
		"ask":             s.Ask != nil,
		"ask_answering":   s.Ask != nil && s.Ask.Answerer != nil,
		"search":          s.API != nil,
		"package_sources": s.Publisher != nil,
		"promotion":       s.Promotion != nil,
		"graph":           s.Graph != nil,
		"web":             true,
	})
}

func (s *Server) handleDocuments(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Admin == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "documents unavailable")
		}
		return
	}
	page, pageSize := queryInt(r, "page", 1), queryInt(r, "page_size", 30)
	items, total, err := s.Admin.ListDocumentVersionsPage(r.Context(), enterpriseadmin.PageQuery{
		Page: page, PageSize: pageSize, Query: r.URL.Query().Get("q"), Lifecycle: r.URL.Query().Get("lifecycle"),
		Company: r.URL.Query().Get("company"), SourceDepartment: r.URL.Query().Get("source_department"),
		DocumentType: r.URL.Query().Get("document_type"), MaterialCategory: r.URL.Query().Get("material_category"), DocumentID: r.URL.Query().Get("document_id"),
	})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if items == nil {
		items = []enterpriseadmin.DocumentVersionView{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"documents": items, "total": total, "page": page, "page_size": pageSize})
}

func (s *Server) handleDistill(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Promotion == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "distill unavailable")
		}
		return
	}
	if !requireAdmin(w, r) {
		return
	}
	var req struct {
		VersionID string `json:"version_id"`
		Reason    string `json:"reason"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	var err error
	switch strings.ToLower(strings.TrimSpace(req.Reason)) {
	case "", "user", "manual", "user-marked":
		err = s.Promotion.RecommendManual(r.Context(), req.VersionID)
	case "existing", "existing-question":
		err = s.Promotion.RecommendExistingQuestion(r.Context(), req.VersionID)
	default:
		writeError(w, http.StatusBadRequest, "unsupported distill reason")
		return
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "document.distill.mark", "document_version", req.VersionID, req.VersionID, "SUCCESS", map[string]any{"reason": req.Reason})
	writeJSON(w, http.StatusOK, map[string]any{"version_id": req.VersionID, "status": "RECOMMENDED"})
}

func (s *Server) handleWikis(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Admin == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "wikis unavailable")
		}
		return
	}
	page, pageSize := queryInt(r, "page", 1), queryInt(r, "page_size", 30)
	items, total, err := s.Admin.ListWikisPage(r.Context(), enterpriseadmin.PageQuery{Page: page, PageSize: pageSize, Query: r.URL.Query().Get("q"), Lifecycle: r.URL.Query().Get("lifecycle"), WikiType: r.URL.Query().Get("wiki_type"), WikiID: r.URL.Query().Get("wiki_id"), CanonicalSubjectID: r.URL.Query().Get("canonical_subject_id")})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if items == nil {
		items = []enterpriseadmin.WikiView{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"wikis": items, "total": total, "page": page, "page_size": pageSize})
}

func (s *Server) handleWiki(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Admin == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "wiki unavailable")
		}
		return
	}
	wikiID := strings.TrimSpace(r.URL.Query().Get("id"))
	if wikiID == "" {
		writeError(w, http.StatusBadRequest, "id is required")
		return
	}
	version := uint64(queryInt(r, "version", 0))
	item, err := s.Admin.GetWiki(r.Context(), wikiID, version)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (s *Server) handleEvidence(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Resolver == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "evidence unavailable")
		}
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	if id == "" {
		writeError(w, http.StatusBadRequest, "id is required")
		return
	}
	items, err := s.Resolver.Resolve(r.Context(), []string{id})
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, items[0])
}

func (s *Server) handleEvidenceSource(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Resolver == nil || s.Publisher == nil || s.Publisher.Objects == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "evidence source unavailable")
		}
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	if id == "" {
		writeError(w, http.StatusBadRequest, "id is required")
		return
	}
	items, err := s.Resolver.Resolve(r.Context(), []string{id})
	if err != nil || len(items) != 1 {
		writeError(w, http.StatusNotFound, "evidence unavailable")
		return
	}
	item := items[0]
	info, err := s.Publisher.Objects.Stat(r.Context(), item.ObjectKey)
	if err != nil {
		writeError(w, http.StatusNotFound, "original source unavailable")
		return
	}
	expiry := 10 * time.Minute
	url, err := s.Publisher.Objects.PresignGet(r.Context(), item.ObjectKey, expiry)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.auditRequest(r, "evidence.source.open", "evidence", item.EvidenceID, item.ObjectKey, "SUCCESS", map[string]any{"document_id": item.DocumentID, "version_id": item.VersionID})
	writeJSON(w, http.StatusOK, map[string]any{"evidence_id": item.EvidenceID, "document_id": item.DocumentID, "version_id": item.VersionID, "object_key": item.ObjectKey, "content_type": info.ContentType, "size_bytes": info.Size, "location": item.RawLocation, "view_url": "/api/enterprise/evidence/source/file?id=" + item.EvidenceID, "download_url": url, "expires_at": time.Now().UTC().Add(expiry)})
}

func (s *Server) handleGraph(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Graph == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "graph unavailable")
		}
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		writeJSON(w, http.StatusOK, map[string]any{"relations": []any{}})
		return
	}
	hits, err := s.Graph.SearchBounded(r.Context(), query, queryInt(r, "depth", 2), queryInt(r, "limit", 10))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"relations": hits, "total": len(hits)})
}

func (s *Server) handleAsk(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Ask == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "enterprise ask is unavailable")
		}
		return
	}
	var req enterpriseask.Request
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	result, err := s.Ask.Ask(r.Context(), req)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.auditRequest(r, "knowledge.ask", "search", result.SearchID, "enterprise ask", "SUCCESS", map[string]any{"citation_count": len(result.Citations), "evidence_only": result.EvidenceOnly})
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.API == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "search unavailable")
		}
		return
	}
	var req enterpriseapi.SearchKnowledgeRequest
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	result, err := s.API.SearchKnowledge(r.Context(), req)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handlePackage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Publisher == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "package unavailable")
		}
		return
	}
	var req enterpriseapi.PackageSourcesRequest
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	result, err := s.Publisher.Publish(r.Context(), req.EvidenceIDs)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "source_package.create", "source_package", result.PackageID, result.ObjectKey, "SUCCESS", map[string]any{"evidence_count": len(req.EvidenceIDs), "version_count": len(result.Manifest.Versions)})
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleStatic(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		methodNotAllowed(w)
		return
	}
	root := strings.TrimSpace(s.WebDir)
	if root == "" {
		root = "internal/web/dist"
	}
	absoluteRoot, err := filepath.Abs(root)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "web root unavailable")
		return
	}
	requestPath := strings.TrimPrefix(filepath.Clean("/"+r.URL.Path), "/")
	candidate := filepath.Join(absoluteRoot, filepath.FromSlash(requestPath))
	rel, err := filepath.Rel(absoluteRoot, candidate)
	if err != nil || strings.HasPrefix(rel, "..") {
		writeError(w, http.StatusBadRequest, "invalid path")
		return
	}
	if st, err := os.Stat(candidate); err == nil && !st.IsDir() {
		http.ServeFile(w, r, candidate)
		return
	}
	index := filepath.Join(absoluteRoot, "index.html")
	if _, err := os.Stat(index); err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"service": "enterprise-knowledge-base", "ui": "not-built"})
		return
	}
	http.ServeFile(w, r, index)
}

func (s *Server) security(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if path == "/health" || !strings.HasPrefix(path, "/api/enterprise/") || isPublicAuthPath(path) {
			next.ServeHTTP(w, r)
			return
		}
		if s.Token != "" && r.Header.Get("Authorization") == "Bearer "+s.Token {
			user := enterpriseauth.User{
				UserID:      "legacy-service-token",
				Username:    "service-token",
				DisplayName: "Service Token",
				Role:        enterpriseauth.RoleAdmin,
				Status:      "ACTIVE",
			}
			next.ServeHTTP(w, withPrincipal(r, user))
			return
		}
		if s.Auth == nil {
			writeError(w, http.StatusUnauthorized, "authentication required")
			return
		}
		cookie, err := r.Cookie(sessionCookieName)
		if err != nil || strings.TrimSpace(cookie.Value) == "" {
			writeError(w, http.StatusUnauthorized, "authentication required")
			return
		}
		user, err := s.Auth.Authenticate(r.Context(), cookie.Value)
		if err != nil {
			s.clearSessionCookie(w)
			writeError(w, http.StatusUnauthorized, "session expired or invalid")
			return
		}
		next.ServeHTTP(w, withPrincipal(r, user))
	})
}

func isPublicAuthPath(path string) bool {
	switch path {
	case "/api/enterprise/auth/status", "/api/enterprise/auth/setup", "/api/enterprise/auth/login":
		return true
	default:
		return false
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, out any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(out); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return err
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]any{"error": message})
}
func methodNotAllowed(w http.ResponseWriter) {
	writeError(w, http.StatusMethodNotAllowed, "method not allowed")
}

func queryInt(r *http.Request, name string, fallback int) int {
	value := strings.TrimSpace(r.URL.Query().Get(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}
