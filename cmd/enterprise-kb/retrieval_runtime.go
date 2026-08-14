package main

import (
	"context"
	"fmt"
	"os"
	"strings"

	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
	enterprisegraph "github.com/xoai/sage-wiki/internal/enterprise/graph"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	"github.com/xoai/sage-wiki/internal/enterprise/retrieval"
)

type retrievalRuntime struct {
	Metadata       *raw.MySQLStore
	Objects        raw.ObjectStore
	Embedder       *raw.OpenAIEmbedder
	Reranker       *raw.HTTPReranker
	RawSearch      *raw.OpenSearchStore
	DerivedSearch  *retrieval.OpenSearchTextStore
	DerivedIndexer *retrieval.DerivedIndexer
	Resolver       *enterpriseevidence.Resolver
	Search         *retrieval.Service
	API            *enterpriseapi.Service
	Publisher      *enterpriseevidence.Publisher
}

func openRetrievalRuntime(ctx context.Context) (*retrievalRuntime, error) {
	metadata, err := raw.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		return nil, err
	}
	fail := func(err error) (*retrievalRuntime, error) {
		_ = metadata.Close()
		return nil, err
	}
	objects, err := raw.NewMinIOStore(
		ctx,
		mustEnv("EKB_MINIO_ENDPOINT"),
		mustEnv("EKB_MINIO_ACCESS_KEY"),
		mustEnv("EKB_MINIO_SECRET_KEY"),
		envOr("EKB_MINIO_BUCKET", "enterprise-kb"),
		false,
	)
	if err != nil {
		return fail(err)
	}
	dims := envPositiveInt("EKB_EMBED_DIMS", 1024)
	modelsURL := mustEnv("EKB_MODELS_URL")
	embedder := &raw.OpenAIEmbedder{
		BaseURL: modelsURL,
		APIKey:  os.Getenv("EKB_MODELS_API_KEY"),
		Model:   envOr("EKB_EMBED_MODEL", "Qwen/Qwen3-Embedding-0.6B"),
		Dims:    dims,
	}
	reranker := &raw.HTTPReranker{
		BaseURL: modelsURL,
		APIKey:  os.Getenv("EKB_MODELS_API_KEY"),
		Model:   envOr("EKB_RERANK_MODEL", "Qwen/Qwen3-Reranker-0.6B"),
	}
	rawSearch := raw.NewOpenSearchStore(mustEnv("EKB_OPENSEARCH_URL"), envOr("EKB_RAW_INDEX", "ekb_raw_v1"))
	if err := rawSearch.EnsureRawIndex(ctx, dims); err != nil {
		return fail(fmt.Errorf("ensure raw index: %w", err))
	}
	derivedSearch := retrieval.NewOpenSearchTextStore(mustEnv("EKB_OPENSEARCH_URL"), envOr("EKB_DERIVED_INDEX", "ekb_derived_v1"))
	if err := derivedSearch.EnsureIndex(ctx, dims); err != nil {
		return fail(fmt.Errorf("ensure derived index: %w", err))
	}
	derivedIndexer := &retrieval.DerivedIndexer{Store: derivedSearch, Embedder: embedder, Metadata: metadata}
	resolver := &enterpriseevidence.Resolver{Source: metadata}
	var summaryLane retrieval.Lane
	if envBool("EKB_RETRIEVAL_ENABLE_SUMMARY", true) {
		summaryLane = &retrieval.TextLane{KnowledgeType: retrieval.TypeSummary, Store: derivedSearch, Embedder: embedder}
	}
	var wikiLane retrieval.Lane
	if envBool("EKB_RETRIEVAL_ENABLE_WIKI", true) {
		wikiLane = &retrieval.TextLane{KnowledgeType: retrieval.TypeWiki, Store: derivedSearch, Embedder: embedder}
	}
	var graphLane retrieval.Lane
	if envBool("EKB_RETRIEVAL_ENABLE_GRAPH", true) {
		neo4jUser, neo4jPassword := neo4jCredentials()
		graphClient := enterprisegraph.NewNeo4jClient(
			envOr("EKB_NEO4J_URL", "http://ekb-neo4j:7474"), neo4jUser, neo4jPassword,
			envOr("EKB_NEO4J_DATABASE", "neo4j"),
		)
		graphLane = &retrieval.GraphLane{Searcher: graphClient, EvidenceFilter: metadata, MaxDepth: 2, MaxExpand: 10}
	}
	search := &retrieval.Service{
		RawLane: &retrieval.RawLane{Store: rawSearch, Embedder: embedder},
		SummaryLane: summaryLane,
		WikiLane: wikiLane,
		GraphLane: graphLane,
		Reranker: reranker,
		Resolver: resolver,
		MaxRerankChars: envPositiveInt("EKB_RERANK_MAX_CHARS", 400),
		MaxRerankCandidates: envPositiveInt("EKB_RERANK_MAX_CANDIDATES", 20),
		Quotas: retrieval.Quotas{
			Raw: envPositiveInt("EKB_RETRIEVAL_RAW_QUOTA", 30),
			Summary: envPositiveInt("EKB_RETRIEVAL_SUMMARY_QUOTA", 20),
			Wiki: envPositiveInt("EKB_RETRIEVAL_WIKI_QUOTA", 20),
			Graph: envPositiveInt("EKB_RETRIEVAL_GRAPH_QUOTA", 10),
		},
	}
	packager := &enterpriseevidence.Packager{Resolver: resolver, Objects: objects}
	publisher := &enterpriseevidence.Publisher{Packager: packager, Objects: objects}
	api := &enterpriseapi.Service{SearchEngine: search, SourcePackager: packager}
	return &retrievalRuntime{
		Metadata: metadata, Objects: objects, Embedder: embedder, Reranker: reranker,
		RawSearch: rawSearch, DerivedSearch: derivedSearch, DerivedIndexer: derivedIndexer,
		Resolver: resolver, Search: search, API: api, Publisher: publisher,
	}, nil
}

func (r *retrievalRuntime) Close() error {
	if r == nil || r.Metadata == nil {
		return nil
	}
	return r.Metadata.Close()
}

func neo4jCredentials() (string, string) {
	user := strings.TrimSpace(os.Getenv("EKB_NEO4J_USER"))
	password := os.Getenv("EKB_NEO4J_PASSWORD")
	if user != "" || password != "" {
		return user, password
	}
	auth := strings.TrimSpace(os.Getenv("EKB_NEO4J_AUTH"))
	if auth == "" || strings.EqualFold(auth, "none") {
		return "", ""
	}
	parts := strings.SplitN(auth, "/", 2)
	if len(parts) != 2 {
		return auth, ""
	}
	return parts[0], parts[1]
}
