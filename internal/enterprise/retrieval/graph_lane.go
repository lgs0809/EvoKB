package retrieval

import (
	"context"
	"fmt"
	"strings"

	enterprisegraph "github.com/xoai/sage-wiki/internal/enterprise/graph"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type GraphSearcher interface {
	SearchBounded(ctx context.Context, query string, maxDepth, maxExpand int) ([]enterprisegraph.SearchHit, error)
}

type EvidenceFilter interface {
	FilterEvidence(ctx context.Context, evidenceIDs []string, filters raw.SearchFilters) ([]string, error)
}

type GraphLane struct {
	Searcher       GraphSearcher
	EvidenceFilter EvidenceFilter
	MaxDepth       int
	MaxExpand      int
}

func (l *GraphLane) Type() string { return TypeGraph }

func (l *GraphLane) Search(ctx context.Context, query string, filters raw.SearchFilters, quota int) ([]Candidate, error) {
	if l == nil || l.Searcher == nil {
		return nil, fmt.Errorf("graph lane searcher is required")
	}
	if quota <= 0 {
		return nil, nil
	}
	maxDepth := l.MaxDepth
	if maxDepth <= 0 || maxDepth > 2 {
		maxDepth = 2
	}
	maxExpand := l.MaxExpand
	if maxExpand <= 0 || maxExpand > 10 {
		maxExpand = 10
	}
	if quota < maxExpand {
		maxExpand = quota
	}
	hits, err := l.Searcher.SearchBounded(ctx, query, maxDepth, maxExpand)
	if err != nil {
		return nil, err
	}
	out := make([]Candidate, 0, len(hits))
	for _, hit := range hits {
		evidenceIDs := hit.EvidenceIDs
		if l.EvidenceFilter != nil {
			evidenceIDs, err = l.EvidenceFilter.FilterEvidence(ctx, evidenceIDs, filters)
			if err != nil {
				return nil, fmt.Errorf("graph lane evidence filter: %w", err)
			}
		} else if !searchFiltersEmpty(filters) {
			return nil, fmt.Errorf("graph lane metadata filters require an evidence filter")
		}
		if len(evidenceIDs) == 0 {
			continue
		}
		knowledge := strings.TrimSpace(fmt.Sprintf("%s --%s--> %s", hit.SourceName, hit.RelationType, hit.TargetName))
		if knowledge == "--" || knowledge == "" {
			continue
		}
		out = append(out, Candidate{
			ID:          hit.RelationID,
			ResultType:  TypeGraph,
			Knowledge:   knowledge,
			LaneScore:   hit.Score,
			EvidenceIDs: evidenceIDs,
		})
	}
	if len(out) > quota {
		out = out[:quota]
	}
	return out, nil
}

func searchFiltersEmpty(filters raw.SearchFilters) bool {
	return filters.SourceDepartment == "" &&
		filters.MaterialCategory == "" &&
		filters.Company == "" &&
		filters.DocumentType == "" &&
		len(filters.VersionIDs) == 0
}
