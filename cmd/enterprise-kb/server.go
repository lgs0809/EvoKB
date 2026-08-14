package main

import (
	"context"
	"flag"
	"os"
	"time"

	enterpriseadmin "github.com/xoai/sage-wiki/internal/enterprise/admin"
	enterpriseauth "github.com/xoai/sage-wiki/internal/enterprise/auth"
	enterprisegraph "github.com/xoai/sage-wiki/internal/enterprise/graph"
	enterprisehttp "github.com/xoai/sage-wiki/internal/enterprise/http"
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
	ingestEngine, closeIngest := mustEngine(ctx, true, false)
	defer closeIngest()

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
		Admin:               adminStore,
		API:                 runtime.API,
		Auth:                authStore,
		Promotion:           runtime.Promotion,
		Resolver:            runtime.Resolver,
		Publisher:           runtime.Publisher,
		Graph:               graphClient,
		Ingest:              ingestEngine,
		WebDir:              *webDir,
		Token:               os.Getenv("EKB_SERVER_TOKEN"),
		SessionCookieSecure: envBool("EKB_SESSION_COOKIE_SECURE", false),
		MaxUploadBytes:      int64(envPositiveInt("EKB_MAX_UPLOAD_MIB", 512)) << 20,
	}
	if err := server.Serve(ctx, *addr); err != nil {
		fatalf("server: %v", err)
	}
}
