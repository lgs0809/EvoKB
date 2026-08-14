package raw

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
)

type OpenSearchStore struct {
	BaseURL string
	Index   string
	HTTP    *http.Client
}

func NewOpenSearchStore(baseURL, index string) *OpenSearchStore {
	if index == "" {
		index = "ekb_raw_v1"
	}
	return &OpenSearchStore{
		BaseURL: strings.TrimRight(baseURL, "/"), Index: index,
		HTTP: &http.Client{Timeout: 60 * time.Second},
	}
}

func (s *OpenSearchStore) EnsureRawIndex(ctx context.Context, dimensions int) error {
	if dimensions <= 0 {
		return fmt.Errorf("opensearch: embedding dimensions must be > 0")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, s.BaseURL+"/"+url.PathEscape(s.Index), nil)
	if err != nil {
		return err
	}
	resp, err := s.client().Do(req)
	if err != nil {
		return fmt.Errorf("opensearch head index: %w", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	if resp.StatusCode != http.StatusNotFound {
		return fmt.Errorf("opensearch head index: HTTP %d", resp.StatusCode)
	}
	mapping := map[string]any{
		"settings": map[string]any{
			"index": map[string]any{"knn": true, "number_of_shards": 1, "number_of_replicas": 0},
		},
		"mappings": map[string]any{
			"dynamic": "strict",
			"properties": map[string]any{
				"knowledge_type":     map[string]any{"type": "keyword"},
				"chunk_id":           map[string]any{"type": "keyword"},
				"document_id":        map[string]any{"type": "keyword"},
				"version_id":         map[string]any{"type": "keyword"},
				"raw_content":        map[string]any{"type": "text"},
				"normalized_content": map[string]any{"type": "text"},
				"embedding_content":  map[string]any{"type": "text", "index": false},
				"heading_path":       map[string]any{"type": "text", "fields": map[string]any{"keyword": map[string]any{"type": "keyword", "ignore_above": 1024}}},
				"source_block_ids":   map[string]any{"type": "keyword"},
				"page_start":         map[string]any{"type": "integer"},
				"page_end":           map[string]any{"type": "integer"},
				"source_department":  map[string]any{"type": "keyword"},
				"material_category":  map[string]any{"type": "keyword"},
				"manual_tags":        map[string]any{"type": "keyword"},
				"company":            map[string]any{"type": "keyword"},
				"document_date":      map[string]any{"type": "date", "format": "strict_date_optional_time||yyyy-MM-dd"},
				"document_type":      map[string]any{"type": "keyword"},
				"is_active":          map[string]any{"type": "boolean"},
				"content_hash":       map[string]any{"type": "keyword"},
				"evidence_ids":       map[string]any{"type": "keyword"},
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

func (s *OpenSearchStore) IndexRaw(ctx context.Context, chunks []Chunk, evidenceByChunk map[string][]string) error {
	if len(chunks) == 0 {
		return nil
	}
	var body bytes.Buffer
	enc := json.NewEncoder(&body)
	for _, ch := range chunks {
		if len(ch.Embedding) == 0 {
			return fmt.Errorf("opensearch: chunk %s has empty embedding", ch.ID)
		}
		if err := enc.Encode(map[string]any{"index": map[string]any{"_index": s.Index, "_id": ch.ID}}); err != nil {
			return err
		}
		doc := map[string]any{
			"knowledge_type": "RAW", "chunk_id": ch.ID, "document_id": ch.DocumentID, "version_id": ch.VersionID,
			"raw_content": ch.Content, "normalized_content": ch.Normalized, "embedding_content": ch.Content,
			"heading_path": ch.HeadingPath, "source_block_ids": ch.SourceBlockIDs,
			"page_start": ch.PageStart, "page_end": ch.PageEnd,
			"source_department": ch.Metadata.SourceDepartment, "material_category": ch.Metadata.MaterialCategory,
			"manual_tags": ch.Metadata.ManualTags, "company": ch.Metadata.Company, "document_type": ch.Metadata.DocumentType,
			"is_active": false, "content_hash": ch.ContentHash, "evidence_ids": evidenceByChunk[ch.ID], "embedding": ch.Embedding,
		}
		if ch.Metadata.DocumentDate != nil {
			doc["document_date"] = ch.Metadata.DocumentDate.UTC().Format("2006-01-02")
		}
		if err := enc.Encode(doc); err != nil {
			return err
		}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.BaseURL+"/_bulk?refresh=wait_for", &body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-ndjson")
	resp, err := s.client().Do(req)
	if err != nil {
		return fmt.Errorf("opensearch bulk: %w", err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("opensearch bulk: HTTP %d: %s", resp.StatusCode, string(b))
	}
	var result struct {
		Errors bool `json:"errors"`
		Items  []map[string]struct {
			Status int `json:"status"`
			Error  any `json:"error"`
		} `json:"items"`
	}
	if err := json.Unmarshal(b, &result); err != nil {
		return fmt.Errorf("opensearch bulk decode: %w", err)
	}
	if result.Errors {
		return fmt.Errorf("opensearch bulk reported item errors")
	}
	return nil
}

func (s *OpenSearchStore) ActivateVersion(ctx context.Context, documentID, versionID string) error {
	body := map[string]any{
		"script": map[string]any{
			"lang":   "painless",
			"source": "ctx._source.is_active = (ctx._source.version_id == params.version_id)",
			"params": map[string]any{"version_id": versionID},
		},
		"query": map[string]any{"term": map[string]any{"document_id": documentID}},
	}
	_, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_update_by_query?refresh=true&conflicts=proceed", body)
	return err
}

func (s *OpenSearchStore) DeactivateVersion(ctx context.Context, versionID string) error {
	body := map[string]any{
		"script": map[string]any{"lang": "painless", "source": "ctx._source.is_active = false"},
		"query":  map[string]any{"term": map[string]any{"version_id": versionID}},
	}
	_, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_update_by_query?refresh=true&conflicts=proceed", body)
	return err
}

func (s *OpenSearchStore) SearchBM25(ctx context.Context, query string, filters SearchFilters, limit int) ([]Candidate, error) {
	if limit <= 0 {
		limit = 20
	}
	must := []any{map[string]any{"multi_match": map[string]any{
		"query":  query,
		"fields": []string{"raw_content^1.0", "normalized_content^1.2", "heading_path^1.5"},
		"type":   "best_fields",
	}}}
	body := map[string]any{
		"size":  limit,
		"query": map[string]any{"bool": map[string]any{"must": must, "filter": filterClauses(filters)}},
	}
	b, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_search", body)
	if err != nil {
		return nil, err
	}
	return decodeCandidates(b)
}

func (s *OpenSearchStore) SearchVector(ctx context.Context, vector []float32, filters SearchFilters, limit int) ([]Candidate, error) {
	if limit <= 0 {
		limit = 20
	}
	body := map[string]any{
		"size": limit,
		"query": map[string]any{"knn": map[string]any{
			"embedding": map[string]any{"vector": vector, "k": limit, "filter": map[string]any{"bool": map[string]any{"filter": filterClauses(filters)}}},
		}},
	}
	b, err := s.doJSON(ctx, http.MethodPost, "/"+url.PathEscape(s.Index)+"/_search", body)
	if err != nil {
		return nil, err
	}
	return decodeCandidates(b)
}

func filterClauses(f SearchFilters) []any {
	clauses := []any{
		map[string]any{"term": map[string]any{"knowledge_type": "RAW"}},
		map[string]any{"term": map[string]any{"is_active": true}},
	}
	if f.SourceDepartment != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"source_department": f.SourceDepartment}})
	}
	if f.MaterialCategory != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"material_category": f.MaterialCategory}})
	}
	if f.Company != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"company": f.Company}})
	}
	if f.DocumentType != "" {
		clauses = append(clauses, map[string]any{"term": map[string]any{"document_type": f.DocumentType}})
	}
	if len(f.VersionIDs) > 0 {
		clauses = append(clauses, map[string]any{"terms": map[string]any{"version_id": f.VersionIDs}})
	}
	return clauses
}

func decodeCandidates(b []byte) ([]Candidate, error) {
	var resp struct {
		Hits struct {
			Hits []struct {
				Score  float64 `json:"_score"`
				Source struct {
					ChunkID       string   `json:"chunk_id"`
					DocumentID    string   `json:"document_id"`
					VersionID     string   `json:"version_id"`
					RawContent    string   `json:"raw_content"`
					HeadingPath   []string `json:"heading_path"`
					PageStart     *int     `json:"page_start"`
					PageEnd       *int     `json:"page_end"`
					EvidenceIDs   []string `json:"evidence_ids"`
					KnowledgeType string   `json:"knowledge_type"`
				} `json:"_source"`
			} `json:"hits"`
		} `json:"hits"`
	}
	if err := json.Unmarshal(b, &resp); err != nil {
		return nil, fmt.Errorf("opensearch search decode: %w", err)
	}
	out := make([]Candidate, 0, len(resp.Hits.Hits))
	for _, h := range resp.Hits.Hits {
		src := h.Source
		out = append(out, Candidate{
			ChunkID: src.ChunkID, DocumentID: src.DocumentID, VersionID: src.VersionID,
			Content: src.RawContent, HeadingPath: src.HeadingPath, PageStart: src.PageStart, PageEnd: src.PageEnd,
			Score: h.Score, EvidenceIDs: src.EvidenceIDs, KnowledgeType: src.KnowledgeType,
		})
	}
	return out, nil
}

func (s *OpenSearchStore) doJSON(ctx context.Context, method, path string, body any) ([]byte, error) {
	var r io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		r = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, s.BaseURL+path, r)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := s.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("opensearch %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("opensearch %s %s: HTTP %d: %s", method, path, resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return b, nil
}

func (s *OpenSearchStore) client() *http.Client {
	if s.HTTP == nil {
		s.HTTP = &http.Client{Timeout: 60 * time.Second}
	}
	return s.HTTP
}
