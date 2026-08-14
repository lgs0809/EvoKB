package evidence

import (
	"path"
	"strings"
)

func packageVersions(evidence []Resolved) []PackageVersion {
	seen := make(map[string]struct{})
	out := make([]PackageVersion, 0)
	for _, item := range evidence {
		if _, ok := seen[item.VersionID]; ok {
			continue
		}
		seen[item.VersionID] = struct{}{}
		name := path.Base(strings.TrimSpace(item.ObjectKey))
		if name == "." || name == "/" || name == "" {
			name = "source"
		}
		out = append(out, PackageVersion{
			DocumentID: item.DocumentID, VersionID: item.VersionID,
			ObjectKey: item.ObjectKey,
			ArchivePath: path.Join("sources", item.VersionID, name),
		})
	}
	return out
}
