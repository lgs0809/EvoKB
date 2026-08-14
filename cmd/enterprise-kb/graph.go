package main

import (
	"context"
	"flag"
	"strings"
	"time"

	enterprisegraph "github.com/xoai/sage-wiki/internal/enterprise/graph"
	"github.com/xoai/sage-wiki/internal/llm"
)

func runGraph(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("graph", flag.ExitOnError)
	summaryID := fs.String("summary", "", "ACTIVE grounded+covered enterprise Summary ID")
	wikiID := fs.String("wiki", "", "ACTIVE enterprise Wiki ID for deterministic keyword projection")
	wikiVersion := fs.Uint64("wiki-version", 0, "Wiki version used with --wiki")
	_ = fs.Parse(args)

	hasSummary := strings.TrimSpace(*summaryID) != ""
	hasWiki := strings.TrimSpace(*wikiID) != ""
	if hasSummary == hasWiki {
		fatalf("graph: provide exactly one of --summary or --wiki")
	}
	if hasWiki && *wikiVersion == 0 {
		fatalf("graph: --wiki-version is required with --wiki")
	}

	store, err := enterprisegraph.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		fatalf("graph mysql: %v", err)
	}
	defer store.Close()
	neo4jUser, neo4jPassword := neo4jCredentials()
	projector := enterprisegraph.NewNeo4jClient(
		mustEnv("EKB_NEO4J_URL"),
		neo4jUser,
		neo4jPassword,
		envOr("EKB_NEO4J_DATABASE", "neo4j"),
	)

	if hasWiki {
		result, err := (&enterprisegraph.WikiKeywordProjector{Store: store, Projector: projector}).Project(ctx, strings.TrimSpace(*wikiID), *wikiVersion)
		if err != nil {
			fatalf("graph wiki-keyword projection failed: %v", err)
		}
		printJSON(result)
		return
	}

	baseURL := mustFirstEnv("EKB_GRAPH_EXTRACTOR_URL", "EKB_LLM_BASE_URL")
	model := mustFirstEnv("EKB_GRAPH_EXTRACTOR_MODEL", "EKB_LLM_MODEL")
	provider := firstEnvOr("openai-compatible", "EKB_GRAPH_EXTRACTOR_PROVIDER", "EKB_LLM_PROVIDER")
	client, err := llm.NewClient(
		provider,
		firstEnv("EKB_GRAPH_EXTRACTOR_API_KEY", "EKB_LLM_API_KEY"),
		baseURL,
		-1,
		llmExtraParams("EKB_GRAPH_EXTRACTOR_REASONING_EFFORT", "EKB_LLM_REASONING_EFFORT"),
	)
	if err != nil {
		fatalf("graph extractor client: %v", err)
	}
	client.SetCallTimeout(time.Duration(envPositiveInt("EKB_LLM_TIMEOUT_SECONDS", 600)) * time.Second)
	extractor := &enterprisegraph.StructuredExtractor{
		Client:       client,
		Model:        model,
		IdentityName: envOr("EKB_GRAPH_EXTRACTOR_IDENTITY", "api:"+model+"@"+baseURL),
		MaxTokens:    envPositiveInt("EKB_GRAPH_EXTRACTOR_MAX_TOKENS", 8192),
	}
	compiler := &enterprisegraph.Compiler{Store: store, Extractor: extractor, Projector: projector}
	result, err := compiler.CompileSummary(ctx, strings.TrimSpace(*summaryID))
	if err != nil {
		fatalf("graph compile failed: %v", err)
	}
	printJSON(result)
}
