package enterprisehttp

import (
	"io"
	"mime"
	"net/http"
	"path/filepath"
	"strings"
)

func (s *Server) handleEvidenceSourceFile(w http.ResponseWriter, r *http.Request) {
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
	reader, err := s.Publisher.Objects.Get(r.Context(), item.ObjectKey)
	if err != nil {
		writeError(w, http.StatusNotFound, "original source unavailable")
		return
	}
	defer reader.Close()
	contentType := strings.TrimSpace(info.ContentType)
	if contentType == "" || contentType == "application/octet-stream" {
		contentType = mime.TypeByExtension(strings.ToLower(filepath.Ext(item.ObjectKey)))
	}
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Disposition", `inline; filename="`+safeUploadFilename(filepath.Base(item.ObjectKey))+`"`)
	w.Header().Set("Cache-Control", "private, max-age=300")
	if info.Size > 0 {
		w.Header().Set("Content-Length", fmtInt64(info.Size))
	}
	if _, err := io.Copy(w, reader); err != nil {
		return
	}
}

func fmtInt64(value int64) string {
	if value == 0 {
		return "0"
	}
	var buf [32]byte
	i := len(buf)
	negative := value < 0
	if negative {
		value = -value
	}
	for value > 0 {
		i--
		buf[i] = byte('0' + value%10)
		value /= 10
	}
	if negative {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
