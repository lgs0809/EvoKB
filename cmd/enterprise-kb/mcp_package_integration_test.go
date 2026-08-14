package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
	enterprisemcp "github.com/xoai/sage-wiki/internal/enterprise/mcp"
)

func TestEnterpriseMCPPackageSourcesIntegration(t *testing.T) {
	if os.Getenv("EKB_RUN_MCP_INTEGRATION") != "1" {
		t.Skip("EKB_RUN_MCP_INTEGRATION=1 is required")
	}
	evidenceID := os.Getenv("EKB_MCP_TEST_EVIDENCE_ID")
	if evidenceID == "" {
		t.Skip("EKB_MCP_TEST_EVIDENCE_ID is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	runtime, err := openRetrievalRuntime(ctx)
	if err != nil {
		t.Fatalf("open runtime: %v", err)
	}
	defer runtime.Close()
	server := enterprisemcp.NewServer(runtime.API, runtime.Publisher)
	cli, err := client.NewInProcessClient(server.MCPServer())
	if err != nil {
		t.Fatalf("new client: %v", err)
	}
	defer cli.Close()
	if err := cli.Start(ctx); err != nil {
		t.Fatalf("start client: %v", err)
	}
	if _, err := cli.Initialize(ctx, mcp.InitializeRequest{}); err != nil {
		t.Fatalf("initialize: %v", err)
	}
	result, err := cli.CallTool(ctx, mcp.CallToolRequest{Params: mcp.CallToolParams{
		Name: "package_sources",
		Arguments: map[string]any{"evidence_ids": []any{evidenceID}},
	}})
	if err != nil {
		t.Fatalf("package_sources call: %v", err)
	}
	if result.IsError {
		t.Fatalf("package_sources error: %#v", result.Content)
	}
	text := result.Content[0].(mcp.TextContent).Text
	var published enterpriseevidence.PublishedPackage
	if err := json.Unmarshal([]byte(text), &published); err != nil {
		t.Fatalf("decode package response: %v", err)
	}
	if published.PackageID == "" || published.ObjectKey == "" || published.DownloadURL == "" {
		t.Fatalf("incomplete package response: %#v", published)
	}
	if len(published.Manifest.Evidence) != 1 || len(published.Manifest.Versions) != 1 {
		t.Fatalf("unexpected manifest: %#v", published.Manifest)
	}
	r, err := runtime.Objects.Get(ctx, published.ObjectKey)
	if err != nil {
		t.Fatalf("get published zip: %v", err)
	}
	payload, err := io.ReadAll(r)
	_ = r.Close()
	if err != nil {
		t.Fatalf("read published zip: %v", err)
	}
	zr, err := zip.NewReader(bytes.NewReader(payload), int64(len(payload)))
	if err != nil {
		t.Fatalf("open published zip: %v", err)
	}
	foundManifest := false
	foundSource := false
	for _, file := range zr.File {
		if file.Name == "manifest.json" {
			foundManifest = true
		}
		if len(file.Name) > len("sources/") && file.Name[:len("sources/")] == "sources/" {
			foundSource = true
		}
	}
	if !foundManifest || !foundSource {
		t.Fatalf("zip missing required entries manifest=%v source=%v", foundManifest, foundSource)
	}
}
