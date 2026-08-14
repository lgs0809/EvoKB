package main

import (
	"context"
	"flag"
	"os"

	enterpriseadmin "github.com/xoai/sage-wiki/internal/enterprise/admin"
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
		Admin:     adminStore,
		API:       runtime.API,
		Promotion: runtime.Promotion,
		Resolver:  runtime.Resolver,
		Publisher: runtime.Publisher,
		Graph:     graphClient,
		WebDir:    *webDir,
		Token:     os.Getenv("EKB_SERVER_TOKEN"),
	}
	if err := server.Serve(ctx, *addr); err != nil {
		fatalf("server: %v", err)
	}
}
