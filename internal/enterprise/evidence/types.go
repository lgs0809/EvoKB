package evidence

import (
	"context"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Resolved struct {
	EvidenceID  string         `json:"evidence_id"`
	DocumentID  string         `json:"document_id"`
	VersionID   string         `json:"version_id"`
	ObjectKey   string         `json:"object_key"`
	RawLocation map[string]any `json:"raw_location"`
	RawContent  string         `json:"raw_content"`
	ContentHash string         `json:"content_hash"`
}

type Source interface {
	ResolveEvidence(ctx context.Context, evidenceIDs []string) ([]raw.Evidence, error)
}
