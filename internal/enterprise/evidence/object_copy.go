package evidence

import (
	"archive/zip"
	"context"
	"io"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

func copySourceObject(ctx context.Context, objects raw.ObjectStore, zw *zip.Writer, version PackageVersion) error {
	r, err := objects.Get(ctx, version.ObjectKey)
	if err != nil {
		return err
	}
	defer r.Close()
	entry, err := zw.Create(version.ArchivePath)
	if err != nil {
		return err
	}
	_, err = io.Copy(entry, r)
	return err
}
