package enterprisemcp

import (
	"context"

	"github.com/mark3labs/mcp-go/mcp"
)

func (s *Server) handlePackageSources(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	if s == nil || s.publisher == nil {
		return toolError("package_sources service is unavailable"), nil
	}
	evidenceIDs := argStrings(req.GetArguments(), "evidence_ids")
	if len(evidenceIDs) == 0 {
		return toolError("evidence_ids is required"), nil
	}
	result, err := s.publisher.Publish(ctx, evidenceIDs)
	if err != nil {
		return toolError(err.Error()), nil
	}
	return jsonResult(result), nil
}
