package enterprisehttp

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

const defaultMaxUploadBytes int64 = 512 << 20

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || s.Ingest == nil {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
		} else {
			writeError(w, http.StatusServiceUnavailable, "document upload unavailable")
		}
		return
	}
	if !s.requireUploadPermission(w, r) {
		return
	}
	maxBytes := s.MaxUploadBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxUploadBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes+(1<<20))
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeError(w, http.StatusBadRequest, "invalid multipart upload: "+err.Error())
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, "file is required")
		return
	}
	defer file.Close()
	if header.Size <= 0 {
		writeError(w, http.StatusBadRequest, "file is empty")
		return
	}
	if header.Size > maxBytes {
		writeError(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("file exceeds %d MiB upload limit", maxBytes>>20))
		return
	}

	filename := safeUploadFilename(header.Filename)
	if filename == "" {
		writeError(w, http.StatusBadRequest, "invalid filename")
		return
	}
	tempDir, err := os.MkdirTemp("", "ekb-upload-*")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "cannot create upload workspace")
		return
	}
	defer os.RemoveAll(tempDir)
	path := filepath.Join(tempDir, filename)
	out, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "cannot create temporary upload")
		return
	}
	written, copyErr := io.Copy(out, io.LimitReader(file, maxBytes+1))
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		writeError(w, http.StatusBadRequest, "upload copy failed")
		return
	}
	if written > maxBytes {
		writeError(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("file exceeds %d MiB upload limit", maxBytes>>20))
		return
	}

	metadata := raw.Metadata{
		SourceDepartment: strings.TrimSpace(r.FormValue("source_department")),
		MaterialCategory: strings.TrimSpace(r.FormValue("material_category")),
		Company:          strings.TrimSpace(r.FormValue("company")),
		DocumentType:     strings.TrimSpace(r.FormValue("document_type")),
	}
	if tags := strings.TrimSpace(r.FormValue("tags")); tags != "" {
		for _, tag := range strings.Split(tags, ",") {
			if tag = strings.TrimSpace(tag); tag != "" {
				metadata.ManualTags = append(metadata.ManualTags, tag)
			}
		}
	}
	if dateValue := strings.TrimSpace(r.FormValue("document_date")); dateValue != "" {
		parsed, err := time.Parse("2006-01-02", dateValue)
		if err != nil {
			writeError(w, http.StatusBadRequest, "document_date must use YYYY-MM-DD")
			return
		}
		metadata.DocumentDate = &parsed
	}
	logicalName := strings.TrimSpace(r.FormValue("logical_name"))
	result, err := s.Ingest.IngestPath(r.Context(), raw.IngestRequest{
		Path:        path,
		LogicalName: logicalName,
		SourcePath:  "web-upload:" + filename,
		Metadata:    metadata,
	})
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"document_id":         result.DocumentID,
		"version_id":          result.VersionID,
		"version_no":          result.VersionNo,
		"reused":              result.Reused,
		"retried":             result.Retried,
		"block_count":         result.BlockCount,
		"chunk_count":         result.ChunkCount,
		"evidence_count":      result.EvidenceCount,
		"original_object_key": result.OriginalObjectKey,
		"warnings":            result.Warnings,
	})
}

func safeUploadFilename(value string) string {
	value = strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
	value = filepath.Base(value)
	if value == "." || value == ".." || value == "" {
		return ""
	}
	value = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 || r == '/' || r == '\\' {
			return -1
		}
		return r
	}, value)
	if len([]byte(value)) > 240 {
		ext := filepath.Ext(value)
		base := strings.TrimSuffix(value, ext)
		for len([]byte(base+ext)) > 240 && len(base) > 0 {
			base = base[:len(base)-1]
		}
		value = base + ext
	}
	return value
}
