package raw

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type OpenAIEmbedder struct {
	BaseURL   string
	APIKey    string
	Model     string
	Dims      int
	BatchSize int
	HTTP      *http.Client
}

func (e *OpenAIEmbedder) Dimensions() int { return e.Dims }

func (e *OpenAIEmbedder) Embed(ctx context.Context, texts []string) ([][]float32, error) {
	if len(texts) == 0 {
		return nil, nil
	}
	if e.Dims <= 0 {
		return nil, fmt.Errorf("embedding: dimensions must be configured")
	}
	batchSize := e.BatchSize
	if batchSize <= 0 {
		batchSize = 16
	}
	vectors := make([][]float32, len(texts))
	for start := 0; start < len(texts); start += batchSize {
		end := start + batchSize
		if end > len(texts) {
			end = len(texts)
		}
		batch, err := e.embedBatch(ctx, texts[start:end])
		if err != nil {
			return nil, fmt.Errorf("embedding batch %d-%d: %w", start, end-1, err)
		}
		copy(vectors[start:end], batch)
	}
	return vectors, nil
}

func (e *OpenAIEmbedder) embedBatch(ctx context.Context, texts []string) ([][]float32, error) {
	body := map[string]any{"model": e.Model, "input": texts}
	b, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(e.BaseURL, "/")+"/v1/embeddings", bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if e.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+e.APIKey)
	}
	resp, err := modelHTTP(e.HTTP).Do(req)
	if err != nil {
		return nil, fmt.Errorf("embedding request: %w", err)
	}
	defer resp.Body.Close()
	rawBody, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("embedding HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(rawBody)))
	}
	var out struct {
		Data []struct {
			Index     int       `json:"index"`
			Embedding []float32 `json:"embedding"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rawBody, &out); err != nil {
		return nil, fmt.Errorf("embedding decode: %w", err)
	}
	if len(out.Data) != len(texts) {
		return nil, fmt.Errorf("embedding: expected %d vectors, got %d", len(texts), len(out.Data))
	}
	vectors := make([][]float32, len(texts))
	for _, item := range out.Data {
		if item.Index < 0 || item.Index >= len(vectors) {
			return nil, fmt.Errorf("embedding: invalid index %d", item.Index)
		}
		if len(item.Embedding) != e.Dims {
			return nil, fmt.Errorf("embedding: vector %d dimension %d, want %d", item.Index, len(item.Embedding), e.Dims)
		}
		vectors[item.Index] = item.Embedding
	}
	return vectors, nil
}

type HTTPReranker struct {
	BaseURL string
	APIKey  string
	Model   string
	HTTP    *http.Client
}

func (r *HTTPReranker) Rerank(ctx context.Context, query string, docs []string) ([]float64, error) {
	if len(docs) == 0 {
		return nil, nil
	}
	body := map[string]any{"model": r.Model, "query": query, "documents": docs, "top_n": len(docs)}
	b, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(r.BaseURL, "/")+"/v1/rerank", bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if r.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+r.APIKey)
	}
	resp, err := modelHTTP(r.HTTP).Do(req)
	if err != nil {
		return nil, fmt.Errorf("rerank request: %w", err)
	}
	defer resp.Body.Close()
	rawBody, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("rerank HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(rawBody)))
	}
	var out struct {
		Results []struct {
			Index int     `json:"index"`
			Score float64 `json:"relevance_score"`
		} `json:"results"`
	}
	if err := json.Unmarshal(rawBody, &out); err != nil {
		return nil, fmt.Errorf("rerank decode: %w", err)
	}
	if len(out.Results) == 0 {
		return nil, fmt.Errorf("rerank: empty results")
	}
	scores := make([]float64, len(docs))
	seen := make([]bool, len(docs))
	for _, item := range out.Results {
		if item.Index < 0 || item.Index >= len(docs) {
			return nil, fmt.Errorf("rerank: invalid index %d", item.Index)
		}
		scores[item.Index] = item.Score
		seen[item.Index] = true
	}
	for i, ok := range seen {
		if !ok {
			return nil, fmt.Errorf("rerank: missing score for document %d", i)
		}
	}
	return scores, nil
}

func modelHTTP(c *http.Client) *http.Client {
	if c != nil {
		return c
	}
	return &http.Client{Timeout: 10 * time.Minute}
}
