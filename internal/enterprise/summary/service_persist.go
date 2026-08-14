package summary

import (
	"context"
	"fmt"
	"strings"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type SearchIndexer interface {
	IndexSummary(ctx context.Context, summary StoredSummary, markdown string) error
}

// PersistPrepared stores deterministic Markdown in MinIO first, then records
// the unit/fact/evidence graph in MySQL. A Summary becomes visible only when its
// in-memory quality gates already resolved to ACTIVE. Search indices are
// rebuildable derived state and are updated only after MySQL becomes durable.
func PersistPrepared(ctx context.Context, store *MySQLStore, objects raw.ObjectStore, prepared []PreparedUnit, indexers ...SearchIndexer) ([]CompileResult, error) {
	if store == nil || objects == nil {
		return nil, fmt.Errorf("summary persist: adapters are incomplete")
	}
	out := make([]CompileResult, 0, len(prepared))
	for _, item := range prepared {
		if err := store.UpsertUnit(ctx, item.Source); err != nil {
			return out, err
		}
		result := item.Result
		objectKey := fmt.Sprintf("summary/%s/%s/%s.md", item.Source.VersionID, item.Source.ID, result.Summary.ID)
		content := []byte(result.Markdown)
		if err := objects.Put(ctx, objectKey, strings.NewReader(result.Markdown), int64(len(content)), "text/markdown; charset=utf-8"); err != nil {
			return out, fmt.Errorf("summary persist object: %w", err)
		}
		result.Summary.ObjectKey = objectKey
		result.Summary.ContentHash = contentHash(result.Markdown)
		if err := store.SaveSummary(ctx, result.Summary); err != nil {
			return out, err
		}
		for _, indexer := range indexers {
			if indexer == nil {
				continue
			}
			if err := indexer.IndexSummary(ctx, result.Summary, result.Markdown); err != nil {
				return out, fmt.Errorf("summary derived index: %w", err)
			}
		}
		out = append(out, result)
	}
	return out, nil
}
