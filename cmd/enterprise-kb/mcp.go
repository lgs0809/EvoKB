package main

import (
	"context"
	"flag"

	enterprisemcp "github.com/xoai/sage-wiki/internal/enterprise/mcp"
)

func runMCP(ctx context.Context, args []string) {
	fs := flag.NewFlagSet("mcp", flag.ExitOnError)
	_ = fs.Parse(args)
	runtime, err := openRetrievalRuntime(ctx)
	if err != nil {
		fatalf("mcp runtime: %v", err)
	}
	defer runtime.Close()
	server := enterprisemcp.NewServer(runtime.API, runtime.Publisher)
	if err := server.ServeStdio(); err != nil {
		fatalf("mcp serve: %v", err)
	}
}
