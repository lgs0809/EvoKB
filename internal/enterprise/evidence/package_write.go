package evidence

import (
	"archive/zip"
	"context"
	"encoding/json"
	"time"
)

func (p *Packager) writeZIP(ctx context.Context, zw *zip.Writer, resolved []Resolved) (PackageResult, error) {
	versions := packageVersions(resolved)
	manifest := PackageManifest{SchemaVersion: "1", CreatedAt: time.Now().UTC(), Evidence: resolved, Versions: versions}
	for _, version := range versions {
		if err := p.copyObject(ctx, zw, version); err != nil {
			_ = zw.Close()
			return PackageResult{}, err
		}
	}
	payload, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil { _ = zw.Close(); return PackageResult{}, err }
	entry, err := zw.Create("manifest.json")
	if err != nil { _ = zw.Close(); return PackageResult{}, err }
	if _, err := entry.Write(payload); err != nil { _ = zw.Close(); return PackageResult{}, err }
	if err := zw.Close(); err != nil { return PackageResult{}, err }
	return PackageResult{EvidenceCount: len(resolved), VersionCount: len(versions), Manifest: manifest}, nil
}
