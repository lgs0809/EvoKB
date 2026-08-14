package api

import (
	"context"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	"github.com/xoai/sage-wiki/internal/enterprise/retrieval"
)

type SearchAudit interface {
	RecordDeliveredSearch(ctx context.Context, query string, filters raw.SearchFilters, result *retrieval.SearchResult) error
}
