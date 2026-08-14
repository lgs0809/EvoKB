package retrieval

import (
	"context"
	"fmt"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type RawLane struct {
	Store    raw.SearchStore
	Embedder raw.Embedder
}

func (l *RawLane) Type() string { return TypeRaw }

func (l *RawLane) SearchCandidates(ctx context.Context, query string, filters raw.SearchFilters, quota int) ([]Candidate, error) {
	if l == nil || l.Store == nil || l.Embedder == nil {
		return nil, fmt.Errorf("raw lane adapters are incomplete")
	}
	if quota <= 0 { return nil, nil }
	vectors, err := l.Embedder.Embed(ctx, []string{query})
	if err != nil { return nil, err }
	if len(vectors) != 1 { return nil, fmt.Errorf("raw lane expected one query embedding") }
	bm25, err := l.Store.SearchBM25(ctx, query, filters, quota)
	if err != nil { return nil, err }
	vector, err := l.Store.SearchVector(ctx, vectors[0], filters, quota)
	if err != nil { return nil, err }
	fused := fuseRaw(bm25, vector, quota)
	out := make([]Candidate, 0, len(fused))
	for _, item := range fused {
		out = append(out, Candidate{ID: item.ChunkID, ResultType: TypeRaw, Knowledge: item.Content, LaneScore: item.Score, EvidenceIDs: item.EvidenceIDs})
	}
	return out, nil
}

func (l *RawLane) Search(ctx context.Context, query string, filters raw.SearchFilters, quota int) ([]Candidate, error) {
	return l.SearchCandidates(ctx, query, filters, quota)
}
