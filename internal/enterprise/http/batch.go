package enterprisehttp

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
	enterpriseingest "github.com/xoai/sage-wiki/internal/enterprise/ingest"
)

const (
	defaultMaxBatchUploadBytes   int64 = 4 << 30
	defaultMaxBatchExpandedBytes int64 = 16 << 30
	defaultMaxBatchFiles               = 2000
)

var supportedBatchExtensions = map[string]struct{}{
	".pdf": {}, ".doc": {}, ".docx": {}, ".ppt": {}, ".pptx": {}, ".xls": {}, ".xlsx": {},
	".png": {}, ".jpg": {}, ".jpeg": {}, ".webp": {}, ".bmp": {}, ".tif": {}, ".tiff": {},
	".md": {}, ".txt": {},
}

type batchDefaults struct {
	SourceDepartment string
	MaterialCategory string
	Company          string
	DocumentType     string
	DocumentDate     *time.Time
	ManualTags       []string
}

func (s *Server) handleBatches(w http.ResponseWriter, r *http.Request) {
	if s.Batches == nil {
		writeError(w, http.StatusServiceUnavailable, "batch ingestion unavailable")
		return
	}
	switch r.Method {
	case http.MethodGet:
		s.handleBatchList(w, r)
	case http.MethodPost:
		s.handleBatchCreate(w, r)
	default:
		methodNotAllowed(w)
	}
}

func (s *Server) handleBatchList(w http.ResponseWriter, r *http.Request) {
	p, ok := principalFromRequest(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	items, total, err := s.Batches.ListBatches(r.Context(), enterpriseingest.ListOptions{
		Page: queryInt(r, "page", 1), PageSize: queryInt(r, "page_size", 20),
		Status: r.URL.Query().Get("status"), Query: r.URL.Query().Get("q"),
		ActorID: p.User.UserID, Admin: p.User.Role == enterpriseauth.RoleAdmin,
	})
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if items == nil {
		items = []enterpriseingest.Batch{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"batches": items, "total": total, "page": queryInt(r, "page", 1), "page_size": queryInt(r, "page_size", 20)})
}

func (s *Server) handleBatchCreate(w http.ResponseWriter, r *http.Request) {
	if !s.requireUploadPermission(w, r) {
		return
	}
	p, _ := principalFromRequest(r)
	maxUpload := s.MaxBatchUploadBytes
	if maxUpload <= 0 {
		maxUpload = defaultMaxBatchUploadBytes
	}
	maxExpanded := s.MaxBatchExpandedBytes
	if maxExpanded <= 0 {
		maxExpanded = defaultMaxBatchExpandedBytes
	}
	maxFiles := s.MaxBatchFiles
	if maxFiles <= 0 {
		maxFiles = defaultMaxBatchFiles
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxUpload+(4<<20))
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeError(w, http.StatusBadRequest, "invalid batch upload: "+err.Error())
		return
	}
	if r.MultipartForm != nil {
		defer r.MultipartForm.RemoveAll()
	}
	headers := r.MultipartForm.File["files"]
	if len(headers) == 0 {
		headers = r.MultipartForm.File["file"]
	}
	if len(headers) == 0 {
		writeError(w, http.StatusBadRequest, "at least one file is required")
		return
	}
	defaults, err := parseBatchDefaults(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	root := strings.TrimSpace(s.StagingRoot)
	if root == "" {
		root = filepath.Join(os.TempDir(), "enterprise-kb-staging")
	}
	stageDir := filepath.Join(root, "batch-"+uuid.NewString())
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		writeError(w, http.StatusInternalServerError, "cannot create batch staging directory")
		return
	}
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.RemoveAll(stageDir)
		}
	}()
	relPaths := r.MultipartForm.Value["relative_paths"]
	var drafts []enterpriseingest.FileDraft
	var rejected []string
	var expandedBytes int64
	for i, header := range headers {
		rel := header.Filename
		if i < len(relPaths) && strings.TrimSpace(relPaths[i]) != "" {
			rel = relPaths[i]
		}
		rel = safeRelativePath(rel)
		if rel == "" {
			rejected = append(rejected, header.Filename+": invalid path")
			continue
		}
		if strings.EqualFold(filepath.Ext(header.Filename), ".zip") {
			archivePath, _, _, err := stageMultipartFile(header, stageDir, "archive-"+uuid.NewString()+".zip", maxUpload)
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			extracted, rejectedEntries, used, err := extractZIPDrafts(archivePath, stageDir, defaults, maxFiles-len(drafts), maxExpanded-expandedBytes)
			_ = os.Remove(archivePath)
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			drafts = append(drafts, extracted...)
			rejected = append(rejected, rejectedEntries...)
			expandedBytes += used
		} else {
			if !supportedDocument(header.Filename) {
				rejected = append(rejected, rel+": unsupported file type")
				continue
			}
			path, size, hash, err := stageMultipartFile(header, stageDir, uuid.NewString()+"-"+safeUploadFilename(header.Filename), maxExpanded-expandedBytes)
			if err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			expandedBytes += size
			drafts = append(drafts, draftFor(rel, header.Filename, path, size, hash, defaults))
		}
		if len(drafts) > maxFiles || expandedBytes > maxExpanded {
			writeError(w, http.StatusRequestEntityTooLarge, "expanded batch exceeds configured limit")
			return
		}
	}
	if len(drafts) == 0 {
		writeError(w, http.StatusBadRequest, "batch contains no supported files")
		return
	}
	batch, err := s.Batches.CreateBatch(r.Context(), p.User.UserID, p.User.Username, len(headers), drafts)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	cleanup = false
	s.auditRequest(r, "document.batch.create", "ingestion_batch", batch.BatchID, fmt.Sprintf("%d files", batch.FileCount), "SUCCESS", map[string]any{"file_count": batch.FileCount, "source_count": len(headers), "rejected_count": len(rejected)})
	writeJSON(w, http.StatusCreated, map[string]any{"batch": batch, "rejected": rejected})
}

func (s *Server) handleBatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || s.Batches == nil {
		if r.Method != http.MethodGet {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "batch ingestion unavailable")
		}
		return
	}
	batch, ok := s.authorizedBatch(w, r, strings.TrimSpace(r.URL.Query().Get("id")))
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, batch)
}

func (s *Server) handleBatchFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPatch || s.Batches == nil {
		if r.Method != http.MethodPatch {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "batch ingestion unavailable")
		}
		return
	}
	fileID := strings.TrimSpace(r.URL.Query().Get("id"))
	file, err := s.Batches.GetFile(r.Context(), fileID)
	if err != nil {
		writeError(w, http.StatusNotFound, "ingestion file not found")
		return
	}
	if _, ok := s.authorizedBatch(w, r, file.BatchID); !ok {
		return
	}
	var req struct {
		LogicalName      string   `json:"logical_name"`
		SourceDepartment string   `json:"source_department"`
		MaterialCategory string   `json:"material_category"`
		Company          string   `json:"company"`
		DocumentType     string   `json:"document_type"`
		DocumentDate     string   `json:"document_date"`
		ManualTags       []string `json:"manual_tags"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	logical, dept, category, company, docType, tags := req.LogicalName, req.SourceDepartment, req.MaterialCategory, req.Company, req.DocumentType, req.ManualTags
	patch := enterpriseingest.FilePatch{LogicalName: &logical, SourceDepartment: &dept, MaterialCategory: &category, Company: &company, DocumentType: &docType, ManualTags: &tags}
	if strings.TrimSpace(req.DocumentDate) == "" {
		patch.ClearDocumentDate = true
	} else {
		parsed, err := time.Parse("2006-01-02", req.DocumentDate)
		if err != nil {
			writeError(w, http.StatusBadRequest, "document_date must use YYYY-MM-DD")
			return
		}
		patch.DocumentDate = &parsed
	}
	updated, err := s.Batches.UpdateFile(r.Context(), fileID, patch)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "document.batch.file.update", "ingestion_file", fileID, updated.LogicalName, "SUCCESS", map[string]any{"batch_id": updated.BatchID})
	writeJSON(w, http.StatusOK, map[string]any{"file": updated})
}

func (s *Server) handleBatchStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Batches == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "batch ingestion unavailable")
		}
		return
	}
	if !s.requireUploadPermission(w, r) {
		return
	}
	var req struct {
		BatchID string `json:"batch_id"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	batch, ok := s.authorizedBatch(w, r, strings.TrimSpace(req.BatchID))
	if !ok {
		return
	}
	if err := s.Batches.QueueBatch(r.Context(), batch.BatchID); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "document.batch.start", "ingestion_batch", batch.BatchID, fmt.Sprintf("%d files", batch.FileCount), "SUCCESS", map[string]any{"file_count": batch.FileCount})
	updated, _ := s.Batches.GetBatch(r.Context(), batch.BatchID)
	writeJSON(w, http.StatusAccepted, map[string]any{"batch": updated})
}

func (s *Server) handleBatchRetry(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Batches == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "batch ingestion unavailable")
		}
		return
	}
	if !s.requireUploadPermission(w, r) {
		return
	}
	var req struct {
		FileID string `json:"file_id"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		return
	}
	file, err := s.Batches.GetFile(r.Context(), strings.TrimSpace(req.FileID))
	if err != nil {
		writeError(w, http.StatusNotFound, "ingestion file not found")
		return
	}
	if _, ok := s.authorizedBatch(w, r, file.BatchID); !ok {
		return
	}
	if err := s.Batches.RetryFile(r.Context(), file.FileID); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.auditRequest(r, "document.ingest.retry", "ingestion_file", file.FileID, file.LogicalName, "SUCCESS", map[string]any{"batch_id": file.BatchID})
	writeJSON(w, http.StatusAccepted, map[string]any{"status": "QUEUED"})
}

func (s *Server) authorizedBatch(w http.ResponseWriter, r *http.Request, batchID string) (enterpriseingest.Batch, bool) {
	if batchID == "" {
		writeError(w, http.StatusBadRequest, "batch id is required")
		return enterpriseingest.Batch{}, false
	}
	p, ok := principalFromRequest(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return enterpriseingest.Batch{}, false
	}
	batch, err := s.Batches.GetBatch(r.Context(), batchID)
	if err != nil {
		writeError(w, http.StatusNotFound, "batch not found")
		return enterpriseingest.Batch{}, false
	}
	if p.User.Role != enterpriseauth.RoleAdmin && batch.CreatedBy != p.User.UserID {
		writeError(w, http.StatusForbidden, "batch belongs to another user")
		return enterpriseingest.Batch{}, false
	}
	return batch, true
}

func parseBatchDefaults(r *http.Request) (batchDefaults, error) {
	out := batchDefaults{SourceDepartment: strings.TrimSpace(r.FormValue("source_department")), MaterialCategory: strings.TrimSpace(r.FormValue("material_category")), Company: strings.TrimSpace(r.FormValue("company")), DocumentType: strings.TrimSpace(r.FormValue("document_type"))}
	if value := strings.TrimSpace(r.FormValue("document_date")); value != "" {
		parsed, err := time.Parse("2006-01-02", value)
		if err != nil {
			return batchDefaults{}, fmt.Errorf("document_date must use YYYY-MM-DD")
		}
		out.DocumentDate = &parsed
	}
	if value := strings.TrimSpace(r.FormValue("tags")); value != "" {
		for _, tag := range strings.Split(value, ",") {
			if tag = strings.TrimSpace(tag); tag != "" {
				out.ManualTags = append(out.ManualTags, tag)
			}
		}
	}
	return out, nil
}

func extractZIPDrafts(archivePath, stageDir string, defaults batchDefaults, maxFiles int, maxBytes int64) ([]enterpriseingest.FileDraft, []string, int64, error) {
	if maxFiles <= 0 {
		return nil, nil, 0, fmt.Errorf("batch file count limit exceeded")
	}
	zr, err := zip.OpenReader(archivePath)
	if err != nil {
		return nil, nil, 0, fmt.Errorf("open zip: %w", err)
	}
	defer zr.Close()
	var drafts []enterpriseingest.FileDraft
	var rejected []string
	var total int64
	for _, entry := range zr.File {
		if entry.FileInfo().IsDir() {
			continue
		}
		rel := safeRelativePath(entry.Name)
		if rel == "" {
			rejected = append(rejected, entry.Name+": unsafe path")
			continue
		}
		if !supportedDocument(rel) {
			rejected = append(rejected, rel+": unsupported file type")
			continue
		}
		if len(drafts) >= maxFiles {
			return nil, nil, total, fmt.Errorf("zip contains more than %d supported files", maxFiles)
		}
		if entry.UncompressedSize64 > uint64(maxBytes-total) {
			return nil, nil, total, fmt.Errorf("expanded zip exceeds configured size limit")
		}
		rc, err := entry.Open()
		if err != nil {
			return nil, nil, total, err
		}
		name := safeUploadFilename(filepath.Base(rel))
		path := filepath.Join(stageDir, uuid.NewString()+"-"+name)
		out, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
		if err != nil {
			_ = rc.Close()
			return nil, nil, total, err
		}
		h := sha256.New()
		written, copyErr := io.Copy(io.MultiWriter(out, h), io.LimitReader(rc, maxBytes-total+1))
		closeErr := out.Close()
		_ = rc.Close()
		if copyErr != nil || closeErr != nil {
			return nil, nil, total, fmt.Errorf("extract %s failed", rel)
		}
		if written > maxBytes-total {
			_ = os.Remove(path)
			return nil, nil, total, fmt.Errorf("expanded zip exceeds configured size limit")
		}
		total += written
		drafts = append(drafts, draftFor(rel, name, path, written, hex.EncodeToString(h.Sum(nil)), defaults))
	}
	return drafts, rejected, total, nil
}

func stageMultipartFile(header *multipart.FileHeader, stageDir, name string, maxBytes int64) (string, int64, string, error) {
	if maxBytes <= 0 {
		return "", 0, "", fmt.Errorf("batch size limit exceeded")
	}
	src, err := header.Open()
	if err != nil {
		return "", 0, "", err
	}
	defer src.Close()
	path := filepath.Join(stageDir, safeUploadFilename(name))
	out, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		return "", 0, "", err
	}
	h := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(out, h), io.LimitReader(src, maxBytes+1))
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(path)
		return "", 0, "", fmt.Errorf("stage upload failed")
	}
	if written > maxBytes {
		_ = os.Remove(path)
		return "", 0, "", fmt.Errorf("batch size limit exceeded")
	}
	return path, written, hex.EncodeToString(h.Sum(nil)), nil
}

func draftFor(relativePath, filename, path string, size int64, hash string, defaults batchDefaults) enterpriseingest.FileDraft {
	return enterpriseingest.FileDraft{RelativePath: relativePath, OriginalFilename: filename, StagingPath: path, SizeBytes: size, SHA256: hash, LogicalName: relativePath, SourceDepartment: defaults.SourceDepartment, MaterialCategory: defaults.MaterialCategory, Company: defaults.Company, DocumentType: defaults.DocumentType, DocumentDate: defaults.DocumentDate, ManualTags: append([]string(nil), defaults.ManualTags...)}
}

func safeRelativePath(value string) string {
	value = strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
	value = strings.TrimPrefix(value, "/")
	if value == "" {
		return ""
	}
	cleaned := filepath.ToSlash(filepath.Clean(value))
	if cleaned == "." || cleaned == ".." || strings.HasPrefix(cleaned, "../") || strings.Contains(cleaned, "/../") {
		return ""
	}
	parts := strings.Split(cleaned, "/")
	for i, part := range parts {
		parts[i] = safeUploadFilename(part)
		if parts[i] == "" {
			return ""
		}
	}
	return strings.Join(parts, "/")
}

func supportedDocument(name string) bool {
	_, ok := supportedBatchExtensions[strings.ToLower(filepath.Ext(name))]
	return ok
}
