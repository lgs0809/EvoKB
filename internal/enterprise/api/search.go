package api

import (
	"context"
	"fmt"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

func (s *Service) SearchKnowledge(ctx context.Context, req SearchKnowledgeRequest) (*SearchKnowledgeResponse, error) {
	if s == nil || s.SearchEngine == nil {
		return nil, fmt.Errorf("search_knowledge service is unavailable")
	}
	filters := raw.SearchFilters{
		SourceDepartment: req.Filters.SourceDepartment,
		MaterialCategory: req.Filters.MaterialCategory,
		Company:          req.Filters.Company,
		DocumentType:     req.Filters.DocumentType,
		VersionIDs:       req.Filters.VersionIDs,
	}
	result, err := s.SearchEngine.Search(ctx, req.Query, filters, req.Limit)
	if err != nil {
		return nil, err
	}
	out := &SearchKnowledgeResponse{SearchID: result.SearchID}
	for _, item := range result.Results {
		out.Results = append(out.Results, SearchKnowledgeResult{
			ResultType: item.Candidate.ResultType,
			Knowledge:  item.Candidate.Knowledge,
			Score:      item.Candidate.Score,
			Evidence:   item.Evidence,
		})
	}
	return out, nil
}
