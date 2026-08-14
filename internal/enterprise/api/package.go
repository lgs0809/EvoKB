package api

import (
	"context"
	"fmt"
	"io"

	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
)

func (s *Service) PackageSources(ctx context.Context, req PackageSourcesRequest, dst io.Writer) (enterpriseevidence.PackageResult, error) {
	if s == nil || s.SourcePackager == nil {
		return enterpriseevidence.PackageResult{}, fmt.Errorf("package_sources service is unavailable")
	}
	if dst == nil {
		return enterpriseevidence.PackageResult{}, fmt.Errorf("package_sources destination is required")
	}
	return s.SourcePackager.StreamZIP(ctx, req.EvidenceIDs, dst)
}
