package retrieval

import (
	"context"

	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Lane interface {
	Type() string
	Search(ctx context.Context, query string, filters raw.SearchFilters, quota int) ([]Candidate, error)
}

type Reranker interface {
	Rerank(ctx context.Context, query string, docs []string) ([]float64, error)
}

type EvidenceResolver interface {
	Resolve(ctx context.Context, evidenceIDs []string) ([]enterpriseevidence.Resolved, error)
}
