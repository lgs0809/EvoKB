package enterprisemcp

import (
	"context"
	"strings"

	"github.com/mark3labs/mcp-go/mcp"
	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
)

func (s *Server) handleSearchKnowledge(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	if s == nil || s.api == nil {
		return toolError("search_knowledge service is unavailable"), nil
	}
	args := req.GetArguments()
	query := strings.TrimSpace(argString(args, "query"))
	if query == "" {
		return toolError("query is required"), nil
	}
	request := enterpriseapi.SearchKnowledgeRequest{
		Query: query,
		Limit: argInt(args, "limit"),
		Filters: enterpriseapi.SearchFilters{
			SourceDepartment: strings.TrimSpace(argString(args, "source_department")),
			MaterialCategory: strings.TrimSpace(argString(args, "material_category")),
			Company: strings.TrimSpace(argString(args, "company")),
			DocumentType: strings.TrimSpace(argString(args, "document_type")),
			VersionIDs: argStrings(args, "version_ids"),
		},
	}
	result, err := s.api.SearchKnowledge(ctx, request)
	if err != nil {
		return toolError(err.Error()), nil
	}
	return jsonResult(result), nil
}
