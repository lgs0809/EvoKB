package api

import enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"

type SearchFilters struct {
	SourceDepartment string   `json:"source_department,omitempty"`
	MaterialCategory string   `json:"material_category,omitempty"`
	Company          string   `json:"company,omitempty"`
	DocumentType     string   `json:"document_type,omitempty"`
	VersionIDs       []string `json:"version_ids,omitempty"`
}

type SearchKnowledgeRequest struct {
	Query   string        `json:"query"`
	Filters SearchFilters `json:"filters,omitempty"`
	Limit   int           `json:"limit,omitempty"`
}

type SearchKnowledgeResult struct {
	ResultType string                        `json:"result_type"`
	Knowledge  string                        `json:"knowledge"`
	Score      float64                       `json:"score"`
	Evidence   []enterpriseevidence.Resolved `json:"evidence"`
}

type SearchKnowledgeResponse struct {
	SearchID string                  `json:"search_id"`
	Results  []SearchKnowledgeResult `json:"results"`
}

type PackageSourcesRequest struct {
	EvidenceIDs []string `json:"evidence_ids"`
}
