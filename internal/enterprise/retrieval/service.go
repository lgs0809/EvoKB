package retrieval

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Service struct {
	RawLane     Lane
	SummaryLane Lane
	WikiLane    Lane
	GraphLane   Lane
	Reranker    Reranker
	Resolver    EvidenceResolver
	Quotas      Quotas
	MaxRerankChars int
	MaxRerankCandidates int
}

func (s *Service) Search(ctx context.Context, query string, filters raw.SearchFilters, finalLimit int) (*SearchResult, error) {
	if s == nil || s.Reranker == nil || s.Resolver == nil {
		return nil, fmt.Errorf("retrieval service adapters are incomplete")
	}
	query = strings.TrimSpace(query)
	if query == "" { return nil, fmt.Errorf("query is required") }
	if finalLimit <= 0 { finalLimit = 10 }
	quotas := s.effectiveQuotas()
	lanes := []struct{ lane Lane; quota int }{
		{s.RawLane, quotas.Raw}, {s.SummaryLane, quotas.Summary},
		{s.WikiLane, quotas.Wiki}, {s.GraphLane, quotas.Graph},
	}
	var merged []Candidate
	seen := make(map[string]struct{})
	for _, item := range lanes {
		if item.lane == nil || item.quota <= 0 { continue }
		candidates, err := item.lane.Search(ctx, query, filters, item.quota)
		if err != nil { return nil, fmt.Errorf("%s lane: %w", item.lane.Type(), err) }
		for _, candidate := range candidates {
			candidate.ResultType = item.lane.Type()
			if strings.TrimSpace(candidate.Knowledge) == "" { continue }
			key := candidate.ResultType + "\x00" + candidate.ID
			if _, ok := seen[key]; ok { continue }
			seen[key] = struct{}{}
			merged = append(merged, candidate)
		}
	}
	if len(merged) == 0 { return &SearchResult{SearchID: newSearchID()}, nil }
	maxRerankCandidates := s.MaxRerankCandidates
	if maxRerankCandidates <= 0 { maxRerankCandidates = 20 }
	merged = balancedRerankPool(merged, maxRerankCandidates)
	docs := make([]string, len(merged))
	maxRerankChars := s.MaxRerankChars
	if maxRerankChars <= 0 { maxRerankChars = 400 }
	for i := range merged { docs[i] = boundedRerankText(merged[i].Knowledge, query, maxRerankChars) }
	scores, err := s.Reranker.Rerank(ctx, query, docs)
	if err != nil { return nil, err }
	if len(scores) != len(merged) { return nil, fmt.Errorf("unified rerank score count mismatch") }
	for i := range merged { merged[i].Score = scores[i] }
	sort.SliceStable(merged, func(i, j int) bool { return merged[i].Score > merged[j].Score })
	result := &SearchResult{SearchID: newSearchID()}
	for _, candidate := range merged {
		if len(result.Results) >= finalLimit { break }
		if len(candidate.EvidenceIDs) == 0 { continue }
		evidence, err := s.Resolver.Resolve(ctx, candidate.EvidenceIDs)
		if err != nil || len(evidence) == 0 { continue }
		result.Results = append(result.Results, Result{Candidate: candidate, Evidence: evidence})
	}
	return result, nil
}

func (s *Service) effectiveQuotas() Quotas {
	q := s.Quotas
	if q.Raw <= 0 { q.Raw = 30 }
	if q.Summary <= 0 { q.Summary = 20 }
	if q.Wiki <= 0 { q.Wiki = 20 }
	if q.Graph <= 0 { q.Graph = 10 }
	return q
}
