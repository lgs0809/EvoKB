package evidence

import (
	"archive/zip"
	"context"
	"fmt"
	"io"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Packager struct {
	Resolver *Resolver
	Objects raw.ObjectStore
}

func (p *Packager) StreamZIP(ctx context.Context, evidenceIDs []string, dst io.Writer) (PackageResult, error) {
	if p == nil || p.Resolver == nil || p.Objects == nil || dst == nil {
		return PackageResult{}, fmt.Errorf("package sources dependencies are required")
	}
	resolved, err := p.Resolver.Resolve(ctx, evidenceIDs)
	if err != nil {
		return PackageResult{}, err
	}
	return p.writeZIP(ctx, zip.NewWriter(dst), resolved)
}
