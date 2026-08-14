package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
	"github.com/xoai/sage-wiki/internal/llm"
)

func main() {
	if len(os.Args) < 2 {
		fatalf("usage: enterprise-kb <ingest|search|summary|mcp> [flags]")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	switch os.Args[1] {
	case "ingest":
		runIngest(ctx, os.Args[2:])
	case "search":
		runSearch(ctx, os.Args[2:])
	case "summary":
		runSummary(ctx, os.Args[2:])
	case "mcp":
		runMCP(ctx, os.Args[2:])
	default:
		fatalf("unknown command %q", os.Args[1])
	}
}

func runIngest(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("ingest", flag.ExitOnError)
	path := fs.String("file", "", "source file path")
	logicalName := fs.String("logical-name", "", "logical document name")
	department := fs.String("department", "", "source department")
	category := fs.String("category", "", "material category")
	company := fs.String("company", "", "company")
	docType := fs.String("document-type", "", "document type")
	tags := fs.String("tags", "", "comma-separated manual tags")
	_ = fs.Parse(args)
	if strings.TrimSpace(*path) == "" {
		fatalf("ingest: --file is required")
	}

	engine, closeFn := mustEngine(ctx, true, false)
	defer closeFn()
	md := raw.Metadata{
		SourceDepartment: *department,
		MaterialCategory: *category,
		Company:          *company,
		DocumentType:     *docType,
	}
	if strings.TrimSpace(*tags) != "" {
		for _, tag := range strings.Split(*tags, ",") {
			if tag = strings.TrimSpace(tag); tag != "" {
				md.ManualTags = append(md.ManualTags, tag)
			}
		}
	}
	result, err := engine.IngestPath(ctx, raw.IngestRequest{
		Path:        *path,
		LogicalName: *logicalName,
		SourcePath:  *path,
		Metadata:    md,
	})
	if err != nil {
		fatalf("ingest failed: %v", err)
	}
	printJSON(result)
}

func runSearch(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("search", flag.ExitOnError)
	query := fs.String("q", "", "query")
	department := fs.String("department", "", "source department")
	category := fs.String("category", "", "material category")
	company := fs.String("company", "", "company")
	docType := fs.String("document-type", "", "document type")
	candidates := fs.Int("candidates", 30, "candidate quota per retrieval lane")
	limit := fs.Int("limit", 10, "final reranked result limit")
	_ = fs.Parse(args)
	if strings.TrimSpace(*query) == "" {
		fatalf("search: --q is required")
	}

	engine, closeFn := mustEngine(ctx, false, true)
	defer closeFn()
	result, err := engine.SearchRaw(ctx, *query, raw.SearchFilters{
		SourceDepartment: *department,
		MaterialCategory: *category,
		Company:          *company,
		DocumentType:     *docType,
	}, *candidates, *limit)
	if err != nil {
		fatalf("search failed: %v", err)
	}
	printJSON(result)
}

func runSummary(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("summary", flag.ExitOnError)
	versionID := fs.String("version", "", "ACTIVE raw document version ID")
	_ = fs.Parse(args)
	if strings.TrimSpace(*versionID) == "" {
		fatalf("summary: --version is required")
	}

	store, err := enterprisesummary.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		fatalf("summary mysql: %v", err)
	}
	defer store.Close()
	objects, err := raw.NewMinIOStore(
		ctx,
		mustEnv("EKB_MINIO_ENDPOINT"),
		mustEnv("EKB_MINIO_ACCESS_KEY"),
		mustEnv("EKB_MINIO_SECRET_KEY"),
		envOr("EKB_MINIO_BUCKET", "enterprise-kb"),
		false,
	)
	if err != nil {
		fatalf("summary minio: %v", err)
	}

	generatorURL := mustFirstEnv("EKB_SUMMARY_GENERATOR_URL", "EKB_LLM_BASE_URL")
	generatorModel := mustFirstEnv("EKB_SUMMARY_GENERATOR_MODEL", "EKB_LLM_MODEL")
	generatorClient, err := llm.NewClient(
		"openai-compatible",
		firstEnv("EKB_SUMMARY_GENERATOR_API_KEY", "EKB_LLM_API_KEY"),
		generatorURL,
		-1,
	)
	if err != nil {
		fatalf("summary generator client: %v", err)
	}
	judgeURL := mustFirstEnv("EKB_SUMMARY_JUDGE_URL", "EKB_LLM_BASE_URL")
	judgeModel := mustFirstEnv("EKB_SUMMARY_JUDGE_MODEL", "EKB_LLM_JUDGE_MODEL")
	judgeClient, err := llm.NewClient(
		"openai-compatible",
		firstEnv("EKB_SUMMARY_JUDGE_API_KEY", "EKB_LLM_API_KEY"),
		judgeURL,
		-1,
	)
	if err != nil {
		fatalf("summary judge client: %v", err)
	}
	generatorClient.SetCallTimeout(time.Duration(envPositiveInt("EKB_LLM_TIMEOUT_SECONDS", 600)) * time.Second)
	judgeClient.SetCallTimeout(time.Duration(envPositiveInt("EKB_LLM_TIMEOUT_SECONDS", 600)) * time.Second)

	generator := &enterprisesummary.WindowedGenerator{Base: &enterprisesummary.StructuredGenerator{
		Client: generatorClient,
		Model: generatorModel,
		IdentityName: envOr("EKB_SUMMARY_GENERATOR_IDENTITY", "api:"+generatorModel),
		MaxTokens: envPositiveInt("EKB_SUMMARY_GENERATOR_MAX_TOKENS", 1024),
		MaxSourceChars: envPositiveInt("EKB_SUMMARY_GENERATOR_MAX_SOURCE_CHARS", 160000),
	}}
	judge := &enterprisesummary.WindowedJudge{Base: &enterprisesummary.StructuredJudge{
		Client: judgeClient,
		Model: judgeModel,
		IdentityName: envOr("EKB_SUMMARY_JUDGE_IDENTITY", "api:"+judgeModel),
		MaxTokens: envPositiveInt("EKB_SUMMARY_JUDGE_MAX_TOKENS", 512),
		MaxSourceChars: envPositiveInt("EKB_SUMMARY_JUDGE_MAX_SOURCE_CHARS", 160000),
	}}
	generator.WindowChars = envPositiveInt("EKB_SUMMARY_GENERATOR_WINDOW_CHARS", 12000)
	judge.WindowChars = envPositiveInt("EKB_SUMMARY_JUDGE_WINDOW_CHARS", 8000)
	service := &enterprisesummary.Service{Store: store, Generator: generator, Judge: judge}
	prepared, err := service.PrepareVersion(ctx, *versionID)
	if err != nil {
		fatalf("summary prepare failed: %v", err)
	}
	retrievalRuntime, err := openRetrievalRuntime(ctx)
	if err != nil {
		fatalf("summary retrieval runtime: %v", err)
	}
	defer retrievalRuntime.Close()
	results, err := enterprisesummary.PersistPrepared(ctx, store, objects, prepared, retrievalRuntime.DerivedIndexer)
	if err != nil {
		fatalf("summary persist failed: %v", err)
	}
	printJSON(results)
}

func mustEngine(ctx context.Context, needParser, needReranker bool) (*raw.Engine, func()) {
	mysqlDSN := mustEnv("EKB_MYSQL_DSN")
	metadata, err := raw.OpenMySQL(ctx, mysqlDSN)
	if err != nil {
		fatalf("mysql: %v", err)
	}
	closeFn := func() { _ = metadata.Close() }

	objects, err := raw.NewMinIOStore(
		ctx,
		mustEnv("EKB_MINIO_ENDPOINT"),
		mustEnv("EKB_MINIO_ACCESS_KEY"),
		mustEnv("EKB_MINIO_SECRET_KEY"),
		envOr("EKB_MINIO_BUCKET", "enterprise-kb"),
		false,
	)
	if err != nil {
		closeFn()
		fatalf("minio: %v", err)
	}

	dims, err := strconv.Atoi(envOr("EKB_EMBED_DIMS", "1024"))
	if err != nil || dims <= 0 {
		closeFn()
		fatalf("invalid EKB_EMBED_DIMS")
	}
	modelsURL := mustEnv("EKB_MODELS_URL")
	engine := &raw.Engine{
		Objects:  objects,
		Metadata: metadata,
		Search: raw.NewOpenSearchStore(
			mustEnv("EKB_OPENSEARCH_URL"),
			envOr("EKB_RAW_INDEX", "ekb_raw_v1"),
		),
		Embedder: &raw.OpenAIEmbedder{
			BaseURL: modelsURL,
			APIKey:  os.Getenv("EKB_MODELS_API_KEY"),
			Model:   envOr("EKB_EMBED_MODEL", "Qwen/Qwen3-Embedding-0.6B"),
			Dims:    dims,
		},
	}
	if needParser {
		engine.Parser = raw.NewMinerUClient(mustEnv("EKB_MINERU_URL"))
	}
	if needReranker {
		engine.Reranker = &raw.HTTPReranker{
			BaseURL: modelsURL,
			APIKey:  os.Getenv("EKB_MODELS_API_KEY"),
			Model:   envOr("EKB_RERANK_MODEL", "Qwen/Qwen3-Reranker-0.6B"),
		}
	}
	return engine, closeFn
}

func mustEnv(name string) string {
	v := strings.TrimSpace(os.Getenv(name))
	if v == "" {
		fatalf("required environment variable %s is empty", name)
	}
	return v
}

func envOr(name, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return fallback
}

func printJSON(v any) {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		fatalf("encode result: %v", err)
	}
}

func fatalf(format string, args ...any) {
	_, _ = fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
