package main

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
	enterprisemcp "github.com/xoai/sage-wiki/internal/enterprise/mcp"
)

func TestEnterpriseMCPRawIntegration(t *testing.T) {
	if os.Getenv("EKB_RUN_MCP_INTEGRATION") != "1" {
		t.Skip("EKB_RUN_MCP_INTEGRATION=1 is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
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
	tools, err := cli.ListTools(ctx, mcp.ListToolsRequest{})
	if err != nil {
		t.Fatalf("list tools: %v", err)
	}
	if len(tools.Tools) != 2 {
		t.Fatalf("expected exactly 2 tools, got %d", len(tools.Tools))
	}
	seen := map[string]bool{}
	for _, tool := range tools.Tools {
		seen[tool.Name] = true
	}
	if !seen["search_knowledge"] || !seen["package_sources"] {
		t.Fatalf("unexpected enterprise tools: %#v", seen)
	}
	result, err := cli.CallTool(ctx, mcp.CallToolRequest{Params: mcp.CallToolParams{
		Name: "search_knowledge",
		Arguments: map[string]any{"query": "黑龙江省重点工程信号评估平台", "limit": float64(3)},
	}})
	if err != nil {
		t.Fatalf("search_knowledge call: %v", err)
	}
	if result.IsError {
		t.Fatalf("search_knowledge error: %#v", result.Content)
	}
	if len(result.Content) != 1 {
		t.Fatalf("unexpected MCP content count: %d", len(result.Content))
	}
	text, ok := result.Content[0].(mcp.TextContent)
	if !ok {
		t.Fatalf("unexpected MCP content type: %T", result.Content[0])
	}
	var response enterpriseapi.SearchKnowledgeResponse
	if err := json.Unmarshal([]byte(text.Text), &response); err != nil {
		t.Fatalf("decode search response: %v", err)
	}
	if response.SearchID == "" || len(response.Results) == 0 {
		t.Fatalf("empty search response: %#v", response)
	}
	if response.Results[0].ResultType != "RAW" {
		t.Fatalf("raw-only integration returned %q", response.Results[0].ResultType)
	}
	if len(response.Results[0].Evidence) == 0 || response.Results[0].Evidence[0].VersionID == "" || response.Results[0].Evidence[0].RawContent == "" {
		t.Fatalf("result is not raw-resolvable: %#v", response.Results[0])
	}
}
