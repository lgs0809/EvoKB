package retrieval

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type TextDocument struct {
	ID            string
	GroupID       string
	KnowledgeType string
	DocumentID    string
	VersionID     string
	DocumentIDs   []string
	VersionIDs    []string
	Content       string
	ContentHash   string
	EvidenceIDs   []string
	Metadata      raw.Metadata
	SourceDepartments []string
	MaterialCategories []string
	Companies []string
	DocumentTypes []string
	Embedding     []float32
	Active        bool
}

type TextHit struct {
	ID          string
	Content     string
	Score       float64
	EvidenceIDs []string
}

type OpenSearchTextStore struct {
	BaseURL string
	Index   string
	HTTP    *http.Client
}

func NewOpenSearchTextStore(baseURL, index string) *OpenSearchTextStore {
	if strings.TrimSpace(index) == "" {
		index = "ekb_derived_v1"
	}
	return &OpenSearchTextStore{
		BaseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"),
		Index:   index,
		HTTP:    &http.Client{Timeout: 60 * time.Second},
	}
}

func (s *OpenSearchTextStore) EnsureIndex(ctx context.Context, dimensions int) error {
	if dimensions <= 0 {
		return fmt.Errorf("derived opensearch: embedding dimensions must be > 0")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, s.BaseURL+"/"+url.PathEscape(s.Index), nil)
	if err != nil {
		return err
	}
	resp, err := s.client().Do(req)
	if err != nil {
		return fmt.Errorf("derived opensearch head index: %w", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	if resp.StatusCode != http.StatusNotFound {
		return fmt.Errorf("derived opensearch head index: HTTP %d", resp.StatusCode)
	}
	mapping := map[string]any{
		"settings": map[string]any{"index": map[string]any{"knn": true, "number_of_shards": 1, "number_of_replicas": 0}},
		"mappings": map[string]any{
			"dynamic": "strict",
			"properties": map[string]any{
				"knowledge_type": map[string]any{"type": "keyword"},
				"knowledge_id": map[string]any{"type": "keyword"},
				"group_id": map[string]any{"type": "keyword"},
				"document_id": map[string]any{"type": "keyword"},
				"version_id": map[string]any{"type": "keyword"},
				"content": map[string]any{"type": "text"},
				"content_hash": map[string]any{"type": "keyword"},
				"evidence_ids": map[string]any{"type": "keyword"},
				"source_department": map[string]any{"type": "keyword"},
				"material_category": map[string]any{"type": "keyword"},
				"manual_tags": map[string]any{"type": "keyword"},
				"company": map[string]any{"type": "keyword"},
				"document_date": map[string]any{"type": "date", "format": "strict_date_optional_time||yyyy-MM-dd"},
				"document_type": map[string]any{"type": "keyword"},
				"is_active": map[string]any{"type": "boolean"},
				"embedding": map[string]any{
					"type": "knn_vector", "dimension": dimensions,
					"method": map[string]any{"name": "hnsw", "space_type": "cosinesimil", "engine": "lucene", "parameters": map[string]any{"ef_construction": 128, "m": 16}},
				},
			},
		},
	}
	_, err = s.doJSON(ctx, http.MethodPut, "/"+url.PathEscape(s.Index), mapping)
	return err
}

func (s *OpenSearchTextStore) IndexDocument(ctx context.Context, doc TextDocument) error {
	if strings.TrimSpace(doc.ID) == "" || strings.TrimSpace(doc.KnowledgeType) == "" || strings.TrimSpace(doc.Content) == "" {
		return fmt.Errorf("derived opensearch: id, knowledge type and content are required")
	}
	if len(doc.Embedding) == 0 {
		return fmt.Errorf("derived opensearch: document %s has empty embedding", doc.ID)
	}
	body := map[string]any{
		"knowledge_type": doc.KnowledgeType,
		"knowledge_id": doc.ID,
		"group_id": doc.GroupID,
		"document_id": doc.DocumentID,
		"version_id": doc.VersionID,
		"content": doc.Content,
		"content_hash": doc.ContentHash,
		"evidence_ids": doc.EvidenceIDs,
		"source_department": doc.Metadata.SourceDepartment,
		"material_category": doc.Metadata.MaterialCategory,
		"manual_tags": doc.Metadata.ManualTags,
		"company": doc.Metadata.Company,
		"document_type": doc.Metadata.DocumentType,
		"is_active": doc.Active,
		"embedding": doc.Embedding,
	}
	if len(doc.DocumentIDs) > 0 {
		body["document_id"] = doc.DocumentIDs
	}
	if len(doc.VersionIDs) > 0 {
		body["version_id"] = doc.VersionIDs
	}
	if len(doc.SourceDepartments) > 0 {
		body["source_department"] = doc.SourceDepartments
	}
	if len(doc.MaterialCategories) > 0 {
		body["material_category"] = doc.MaterialCategories
	}
	if len(doc.Companies) > 0 {
		body["company"] = doc.Companies
	}
	if len(doc.DocumentTypes) > 0 {
		body["document_type"] = doc.DocumentTypes
	}
	if doc.Metadata.DocumentDate != nil {
		body["document_date"] = doc.Metadata.DocumentDate.UTC().Format("2006-01-02")
	}
	_, err := s.doJSON(ctx, http.MethodPut, "/"+url.PathEscape(s.Index)+"/_doc/"+url.PathEscape(doc.ID)+"?refresh=wait_for", body)
	return err
}

func (s *OpenSearchTextStore) DeactivateGroup(ctx context.Context, knowledgeType, groupID string) error {
	if strings.TrimSpace(groupID) == "" {
		return nil
	}
	body := map[string]any{
		"script": map[string]any{"lang": "painless", "source": "ctx._source.is_active = false"},
		"query": map[string]any{"bool": map[string]any{"filter": []any{
			map[string]any{"term": map[string]any{"knowledge_type": knowledgeType}},
			map[string]any{"term": map[string]any{"group_id": groupID}},
		}}},
	}
	_, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_update_by_query?refresh=true&conflicts=proceed", body)
	return err
}

func (s *OpenSearchTextStore) SearchBM25(ctx context.Context, knowledgeType, query string, filters raw.SearchFilters, limit int) ([]TextHit, error) {
	if limit <= 0 {
		limit = 20
	}
	body := map[string]any{
		"size": limit,
		"query": map[string]any{"bool": map[string]any{
			"must": []any{map[string]any{"match": map[string]any{"content": map[string]any{"query": query}}}},
			"filter": derivedFilterClauses(knowledgeType, filters),
		}},
	}
	payload, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_search", body)
	if err != nil {
		return nil, err
	}
	return decodeTextHits(payload)
}

func (s *OpenSearchTextStore) SearchVector(ctx context.Context, knowledgeType string, vector []float32, filters raw.SearchFilters, limit int) ([]TextHit, error) {
	if limit <= 0 {
		limit = 20
	}
	body := map[string]any{
		"size": limit,
		"query": map[string]any{"knn": map[string]any{
			"embedding": map[string]any{
				"vector": vector,
				"k": limit,
				"filter": map[string]any{"bool": map[string]any{"filter": derivedFilterClauses(knowledgeType, filters)}},
			},
		}},
	}
	payload, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_search", body)
	if err != nil {
		return nil, err
	}
	return decodeTextHits(payload)
}

func derivedFilterClauses(knowledgeType string, filters raw.SearchFilters) []any {
	clauses := []any{
		map[string]any{"term": map[string]any{"knowledge_type": knowledgeType}},
		map[string]any{"term": map[string]any{"is_active": true}},
	}
	if filters.SourceDepartment != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"source_department": filters.SourceDepartment}})
	}
	if filters.MaterialCategory != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"material_category": filters.MaterialCategory}})
	}
	if filters.Company != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"company": filters.Company}})
	}
	if filters.DocumentType != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"document_type": filters.DocumentType}})
	}
	if len(filters.VersionIDs) > 0 {
		clauses = append(clauses, map[string]any{"terms": map[string]any{"version_id": filters.VersionIDs}})
	}
	return clauses
}

func decodeTextHits(payload []byte) ([]TextHit, error) {
	var response struct {
		Hits struct {
			Hits []struct {
				Score float64 `json:"_score"`
				Source struct {
					ID string `json:"knowledge_id"`
					Content string `json:"content"`
					EvidenceIDs []string `json:"evidence_ids"`
				} `json:"_source"`
			} `json:"hits"`
		} `json:"hits"`
	}
	if err := json.Unmarshal(payload, &response); err != nil {
		return nil, fmt.Errorf("derived opensearch search decode: %w", err)
	}
	out := make([]TextHit, 0, len(response.Hits.Hits))
	for _, hit := range response.Hits.Hits {
		out = append(out, TextHit{ID: hit.Source.ID, Content: hit.Source.Content, Score: hit.Score, EvidenceIDs: hit.Source.EvidenceIDs})
	}
	return out, nil
}

func (s *OpenSearchTextStore) doJSON(ctx context.Context, method, path string, body any) ([]byte, error) {
	var reader io.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(ctx, method, s.BaseURL+path, reader)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := s.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("derived opensearch %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("derived opensearch %s %s: HTTP %d: %s", method, path, resp.StatusCode, strings.TrimSpace(string(payload)))
	}
	return payload, nil
}

func (s *OpenSearchTextStore) client() *http.Client {
	if s.HTTP == nil {
		s.HTTP = &http.Client{Timeout: 60 * time.Second}
	}
	return s.HTTP
}
