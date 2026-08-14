package evidence

import (
	"context"
	"fmt"
	"strings"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Resolver struct {
	Source Source
}

func (r *Resolver) Resolve(ctx context.Context, evidenceIDs []string) ([]Resolved, error) {
	if r == nil || r.Source == nil {
		return nil, fmt.Errorf("evidence resolver source is required")
	}
	ids := unique(evidenceIDs)
	if len(ids) == 0 {
		return nil, nil
	}
	items, err := r.Source.ResolveEvidence(ctx, ids)
	if err != nil {
		return nil, err
	}
	return resolveOrdered(ids, items)
}

func resolveOrdered(ids []string, items []raw.Evidence) ([]Resolved, error) {
	byID := make(map[string]raw.Evidence, len(items))
	for _, item := range items {
		byID[item.ID] = item
	}
	out := make([]Resolved, 0, len(ids))
	for _, id := range ids {
		item, ok := byID[id]
		if !ok {
			return nil, fmt.Errorf("evidence %s is unavailable", id)
		}
		if strings.TrimSpace(item.VersionID) == "" || strings.TrimSpace(item.ObjectKey) == "" {
			return nil, fmt.Errorf("evidence %s is not raw-resolvable", id)
		}
		out = append(out, Resolved{
			EvidenceID: item.ID, DocumentID: item.DocumentID, VersionID: item.VersionID,
			ObjectKey: item.ObjectKey, RawLocation: item.Location, RawContent: item.Content,
			ContentHash: item.Hash,
		})
	}
	return out, nil
}
