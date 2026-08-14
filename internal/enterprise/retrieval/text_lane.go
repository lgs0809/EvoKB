package retrieval

import (
	"context"
	"fmt"
	"sort"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type TextSearchStore interface {
	SearchBM25(ctx context.Context, knowledgeType, query string, filters raw.SearchFilters, limit int) ([]TextHit, error)
	SearchVector(ctx context.Context, knowledgeType string, vector []float32, filters raw.SearchFilters, limit int) ([]TextHit, error)
}

type TextLane struct {
	KnowledgeType string
	Store         TextSearchStore
	Embedder      raw.Embedder
}

func (l *TextLane) Type() string { return l.KnowledgeType }

func (l *TextLane) Search(ctx context.Context, query string, filters raw.SearchFilters, quota int) ([]Candidate, error) {
	if l == nil || l.Store == nil || l.Embedder == nil {
		return nil, fmt.Errorf("%s lane adapters are incomplete", l.KnowledgeType)
	}
	if l.KnowledgeType != TypeSummary && l.KnowledgeType != TypeWiki {
		return nil, fmt.Errorf("unsupported derived text knowledge type %q", l.KnowledgeType)
	}
	if quota <= 0 {
		return nil, nil
	}
	vectors, err := l.Embedder.Embed(ctx, []string{query})
	if err != nil {
		return nil, err
	}
	if len(vectors) != 1 {
		return nil, fmt.Errorf("%s lane expected one query embedding", l.KnowledgeType)
	}
	bm25, err := l.Store.SearchBM25(ctx, l.KnowledgeType, query, filters, quota)
	if err != nil {
		return nil, err
	}
	vector, err := l.Store.SearchVector(ctx, l.KnowledgeType, vectors[0], filters, quota)
	if err != nil {
		return nil, err
	}
	fused := fuseTextHits(bm25, vector, quota)
	out := make([]Candidate, 0, len(fused))
	for _, hit := range fused {
		if len(hit.EvidenceIDs) == 0 {
			continue
		}
		out = append(out, Candidate{
			ID:          hit.ID,
			ResultType:  l.KnowledgeType,
			Knowledge:   hit.Content,
			LaneScore:   hit.Score,
			EvidenceIDs: hit.EvidenceIDs,
		})
	}
	return out, nil
}

type textAccumulator struct {
	hit   TextHit
	score float64
}

func fuseTextHits(a, b []TextHit, limit int) []TextHit {
	items := make(map[string]*textAccumulator, len(a)+len(b))
	addTextRanks(items, a)
	addTextRanks(items, b)
	out := make([]TextHit, 0, len(items))
	for _, item := range items {
		item.hit.Score = item.score
		out = append(out, item.hit)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Score == out[j].Score {
			return out[i].ID < out[j].ID
		}
		return out[i].Score > out[j].Score
	})
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out
}

func addTextRanks(items map[string]*textAccumulator, hits []TextHit) {
	for rank, hit := range hits {
		item := items[hit.ID]
		if item == nil {
			item = &textAccumulator{hit: hit}
			items[hit.ID] = item
		} else {
			item.hit.EvidenceIDs = mergeStrings(item.hit.EvidenceIDs, hit.EvidenceIDs)
		}
		item.score += 1.0 / float64(61+rank)
	}
}

func mergeStrings(a, b []string) []string {
	seen := make(map[string]struct{}, len(a)+len(b))
	out := make([]string, 0, len(a)+len(b))
	for _, values := range [][]string{a, b} {
		for _, value := range values {
			if value == "" {
				continue
			}
			if _, ok := seen[value]; ok {
				continue
			}
			seen[value] = struct{}{}
			out = append(out, value)
		}
	}
	return out
}
