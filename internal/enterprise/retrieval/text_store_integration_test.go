package retrieval

import (
	"context"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

func TestDerivedOpenSearchIntegration(t *testing.T) {
	baseURL := strings.TrimSpace(os.Getenv("EKB_OPENSEARCH_URL"))
	modelsURL := strings.TrimSpace(os.Getenv("EKB_MODELS_URL"))
	if baseURL == "" || modelsURL == "" {
		t.Skip("EKB_OPENSEARCH_URL and EKB_MODELS_URL are required for integration test")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	index := fmt.Sprintf("ekb_derived_it_%d", time.Now().UTC().UnixNano())
	store := NewOpenSearchTextStore(baseURL, index)
	defer func() { _, _ = store.doJSON(context.Background(), "DELETE", "/"+index, nil) }()
	embedder := &raw.OpenAIEmbedder{
		BaseURL: modelsURL,
		APIKey: os.Getenv("EKB_MODELS_API_KEY"),
		Model: "Qwen/Qwen3-Embedding-0.6B",
		Dims: 1024,
	}
	vectors, err := embedder.Embed(ctx, []string{"恒安嘉新签署重大项目合同，合同金额为一亿元。"})
	if err != nil {
		t.Fatalf("embed: %v", err)
	}
	if err := store.EnsureIndex(ctx, 1024); err != nil {
		t.Fatalf("ensure index: %v", err)
	}
	if err := store.IndexDocument(ctx, TextDocument{
		ID: "summary-it-1", GroupID: "unit-it-1", KnowledgeType: TypeSummary,
		DocumentID: "doc-it-1", VersionID: "version-it-1",
		Content: "恒安嘉新签署重大项目合同，合同金额为一亿元。",
		ContentHash: strings.Repeat("a", 64), EvidenceIDs: []string{"evidence-it-1"},
		Metadata: raw.Metadata{Company: "恒安嘉新", SourceDepartment: "法务部", DocumentType: "合同/协议"},
		Embedding: vectors[0], Active: true,
	}); err != nil {
		t.Fatalf("index: %v", err)
	}
	bm25, err := store.SearchBM25(ctx, TypeSummary, "重大项目合同", raw.SearchFilters{Company: "恒安嘉新"}, 5)
	if err != nil {
		t.Fatalf("bm25: %v", err)
	}
	if len(bm25) != 1 || bm25[0].ID != "summary-it-1" || len(bm25[0].EvidenceIDs) != 1 {
		t.Fatalf("unexpected bm25 hits: %#v", bm25)
	}
	blocked, err := store.SearchBM25(ctx, TypeSummary, "重大项目合同", raw.SearchFilters{Company: "其他公司"}, 5)
	if err != nil {
		t.Fatalf("filtered bm25: %v", err)
	}
	if len(blocked) != 0 {
		t.Fatalf("metadata filter did not exclude hit: %#v", blocked)
	}
	queryVector, err := embedder.Embed(ctx, []string{"公司签了什么大合同"})
	if err != nil {
		t.Fatalf("query embed: %v", err)
	}
	vectorHits, err := store.SearchVector(ctx, TypeSummary, queryVector[0], raw.SearchFilters{DocumentType: "合同/协议"}, 5)
	if err != nil {
		t.Fatalf("vector search: %v", err)
	}
	if len(vectorHits) == 0 || vectorHits[0].ID != "summary-it-1" {
		t.Fatalf("unexpected vector hits: %#v", vectorHits)
	}
}
