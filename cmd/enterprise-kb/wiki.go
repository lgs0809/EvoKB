package main

import (
	"context"
	"flag"
	"strings"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	enterprisewiki "github.com/xoai/sage-wiki/internal/enterprise/wiki"
	"github.com/xoai/sage-wiki/internal/llm"
)

func runWiki(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("wiki", flag.ExitOnError)
	wikiType := fs.String("type", "", "enterprise Wiki type, for example COMPANY or CONTRACT")
	title := fs.String("title", "", "Wiki title")
	subject := fs.String("subject", "", "optional canonical subject ID")
	summaryIDs := fs.String("summaries", "", "comma-separated ACTIVE grounded+covered Summary IDs")
	_ = fs.Parse(args)

	ids := commaSeparated(*summaryIDs)
	if strings.TrimSpace(*wikiType) == "" || strings.TrimSpace(*title) == "" || len(ids) == 0 {
		fatalf("wiki: --type, --title and --summaries are required")
	}

	store, err := enterprisewiki.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		fatalf("wiki mysql: %v", err)
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
		fatalf("wiki minio: %v", err)
	}

	writerURL := mustFirstEnv("EKB_WIKI_WRITER_URL", "EKB_LLM_BASE_URL")
	writerModel := mustFirstEnv("EKB_WIKI_WRITER_MODEL", "EKB_LLM_MODEL")
	writerProvider := firstEnvOr("openai-compatible", "EKB_WIKI_WRITER_PROVIDER", "EKB_LLM_PROVIDER")
	writerClient, err := llm.NewClient(
		writerProvider,
		firstEnv("EKB_WIKI_WRITER_API_KEY", "EKB_LLM_API_KEY"),
		writerURL,
		-1,
		llmExtraParams("EKB_WIKI_WRITER_REASONING_EFFORT", "EKB_LLM_REASONING_EFFORT"),
	)
	if err != nil {
		fatalf("wiki writer client: %v", err)
	}
	judgeURL := mustFirstEnv("EKB_WIKI_JUDGE_URL", "EKB_LLM_BASE_URL")
	judgeModel := mustFirstEnv("EKB_WIKI_JUDGE_MODEL", "EKB_LLM_JUDGE_MODEL")
	judgeProvider := firstEnvOr("openai-compatible", "EKB_WIKI_JUDGE_PROVIDER", "EKB_LLM_PROVIDER")
	judgeClient, err := llm.NewClient(
		judgeProvider,
		firstEnv("EKB_WIKI_JUDGE_API_KEY", "EKB_LLM_API_KEY"),
		judgeURL,
		-1,
		llmExtraParams("EKB_WIKI_JUDGE_REASONING_EFFORT", "EKB_LLM_REASONING_EFFORT"),
	)
	if err != nil {
		fatalf("wiki judge client: %v", err)
	}
	timeout := time.Duration(envPositiveInt("EKB_LLM_TIMEOUT_SECONDS", 600)) * time.Second
	writerClient.SetCallTimeout(timeout)
	judgeClient.SetCallTimeout(timeout)

	writer := &enterprisewiki.StructuredWriter{
		Client:       writerClient,
		Model:        writerModel,
		IdentityName: envOr("EKB_WIKI_WRITER_IDENTITY", "api:"+writerModel+"@"+writerURL),
		MaxTokens:    envPositiveInt("EKB_WIKI_WRITER_MAX_TOKENS", 8192),
	}
	judge := &enterprisewiki.StructuredJudge{
		Client:       judgeClient,
		Model:        judgeModel,
		IdentityName: envOr("EKB_WIKI_JUDGE_IDENTITY", "api:"+judgeModel+"@"+judgeURL),
		MaxTokens:    envPositiveInt("EKB_WIKI_JUDGE_MAX_TOKENS", 2048),
	}

	runtime, err := openRetrievalRuntime(ctx)
	if err != nil {
		fatalf("wiki retrieval runtime: %v", err)
	}
	defer runtime.Close()
	compiler := &enterprisewiki.Compiler{
		Store: store, Objects: objects, Generator: writer, Judge: judge, Indexer: runtime.DerivedIndexer,
	}
	result, err := compiler.Compile(ctx, enterprisewiki.CompileRequest{
		WikiType: strings.ToUpper(strings.TrimSpace(*wikiType)), Title: strings.TrimSpace(*title),
		CanonicalSubjectID: strings.TrimSpace(*subject), SummaryIDs: ids,
	})
	if err != nil {
		fatalf("wiki compile failed: %v", err)
	}
	printJSON(result)
}

func commaSeparated(value string) []string {
	seen := map[string]struct{}{}
	var out []string
	for _, item := range strings.Split(value, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if _, ok := seen[item]; ok {
			continue
		}
		seen[item] = struct{}{}
		out = append(out, item)
	}
	return out
}
