package main

import (
	"context"
	"flag"
	"os"
	"time"

	enterpriseadmin "github.com/xoai/sage-wiki/internal/enterprise/admin"
	enterpriseaudit "github.com/xoai/sage-wiki/internal/enterprise/audit"
	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
	enterprisegraph "github.com/xoai/sage-wiki/internal/enterprise/graph"
	enterprisehttp "github.com/xoai/sage-wiki/internal/enterprise/http"
	enterpriseingest "github.com/xoai/sage-wiki/internal/enterprise/ingest"
)

func runServer(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("server", flag.ExitOnError)
	addr := fs.String("addr", envOr("EKB_SERVER_ADDR", "127.0.0.1:18080"), "HTTP listen address")
	webDir := fs.String("web-dir", envOr("EKB_WEB_DIR", "internal/web/dist"), "built Preact UI directory")
	_ = fs.Parse(args)

	runtime, err := openRetrievalRuntime(ctx)
	if err != nil {
		fatalf("server retrieval runtime: %v", err)
	}
	defer runtime.Close()
	adminStore, err := enterpriseadmin.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		fatalf("server admin store: %v", err)
	}
	defer adminStore.Close()
	authStore, err := enterpriseauth.OpenMySQL(
		ctx,
		mustEnv("EKB_MYSQL_DSN"),
		time.Duration(envPositiveInt("EKB_SESSION_TTL_HOURS", 12))*time.Hour,
	)
	if err != nil {
		fatalf("server auth store: %v", err)
	}
	defer authStore.Close()
	auditStore, err := enterpriseaudit.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		fatalf("server audit store: %v", err)
	}
	defer auditStore.Close()
	batchStore, err := enterpriseingest.OpenMySQL(ctx, mustEnv("EKB_MYSQL_DSN"))
	if err != nil {
		fatalf("server ingestion store: %v", err)
	}
	defer batchStore.Close()
	ingestEngine, closeIngest := mustEngine(ctx, true, false)
	defer closeIngest()
	worker := &enterpriseingest.Worker{Store: batchStore, Engine: ingestEngine, PollInterval: time.Second}
	go func() { _ = worker.Run(ctx) }()

	var graphClient *enterprisegraph.Neo4jClient
	if envBool("EKB_RETRIEVAL_ENABLE_GRAPH", true) {
		user, password := neo4jCredentials()
		graphClient = enterprisegraph.NewNeo4jClient(
			envOr("EKB_NEO4J_URL", "http://ekb-neo4j:7474"),
			user,
			password,
			envOr("EKB_NEO4J_DATABASE", "neo4j"),
		)
	}
	server := &enterprisehttp.Server{
		Admin:                 adminStore,
		API:                   runtime.API,
		Ask:                   openAskService(ctx, runtime.API),
		Audit:                 auditStore,
		Auth:                  authStore,
		Batches:               batchStore,
		Promotion:             runtime.Promotion,
		Resolver:              runtime.Resolver,
		Publisher:             runtime.Publisher,
		Graph:                 graphClient,
		Ingest:                ingestEngine,
		WebDir:                *webDir,
		Token:                 os.Getenv("EKB_SERVER_TOKEN"),
		SessionCookieSecure:   envBool("EKB_SESSION_COOKIE_SECURE", false),
		TrustProxyHeaders:     envBool("EKB_TRUST_PROXY_HEADERS", false),
		MaxUploadBytes:        int64(envPositiveInt("EKB_MAX_UPLOAD_MIB", 512)) << 20,
		MaxBatchUploadBytes:   int64(envPositiveInt("EKB_MAX_BATCH_UPLOAD_MIB", 4096)) << 20,
		MaxBatchExpandedBytes: int64(envPositiveInt("EKB_MAX_BATCH_EXPANDED_MIB", 16384)) << 20,
		MaxBatchFiles:         envPositiveInt("EKB_MAX_BATCH_FILES", 2000),
		StagingRoot:           envOr("EKB_STAGING_ROOT", "/var/lib/enterprise-kb/staging"),
	}
	if err := server.Serve(ctx, *addr); err != nil {
		fatalf("server: %v", err)
	}
}
