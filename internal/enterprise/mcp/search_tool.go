package enterprisemcp

import "github.com/mark3labs/mcp-go/mcp"

func (s *Server) registerSearchTool() {
	tool := mcp.NewTool("search_knowledge",
		mcp.WithDescription("Search enterprise knowledge with raw-evidence gating."),
		mcp.WithString("query", mcp.Required()),
		mcp.WithString("source_department"),
		mcp.WithString("material_category"),
		mcp.WithString("company"),
		mcp.WithString("document_type"),
		mcp.WithArray("version_ids", mcp.WithStringItems()),
		mcp.WithNumber("limit"),
	)
	s.mcp.AddTool(tool, s.handleSearchKnowledge)
}
