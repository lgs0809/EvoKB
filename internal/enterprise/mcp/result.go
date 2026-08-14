package enterprisemcp

import (
	"encoding/json"

	"github.com/mark3labs/mcp-go/mcp"
)

func jsonResult(value any) *mcp.CallToolResult {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return toolError(err.Error())
	}
	return &mcp.CallToolResult{Content: []mcp.Content{mcp.TextContent{Type: "text", Text: string(data)}}}
}

func toolError(message string) *mcp.CallToolResult {
	return &mcp.CallToolResult{IsError: true, Content: []mcp.Content{mcp.TextContent{Type: "text", Text: message}}}
}
