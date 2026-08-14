package enterprisemcp

import "github.com/mark3labs/mcp-go/mcp"

func (s *Server) registerPackageTool() {
	tool := mcp.NewTool("package_sources",
		mcp.WithDescription("Package exact original source versions for returned evidence IDs."),
		mcp.WithArray("evidence_ids", mcp.Required(), mcp.WithStringItems()),
	)
	s.mcp.AddTool(tool, s.handlePackageSources)
}
