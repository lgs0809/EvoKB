package evidence

import (
	"archive/zip"
	"context"
)

func (p *Packager) copyObject(ctx context.Context, zw *zip.Writer, version PackageVersion) error {
	return copySourceObject(ctx, p.Objects, zw, version)
}
