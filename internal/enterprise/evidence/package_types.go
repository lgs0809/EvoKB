package evidence

import "time"

type PackageVersion struct {
	DocumentID string `json:"document_id"`
	VersionID  string `json:"version_id"`
	ObjectKey  string `json:"object_key"`
	ArchivePath string `json:"archive_path"`
}

type PackageManifest struct {
	SchemaVersion string           `json:"schema_version"`
	CreatedAt     time.Time        `json:"created_at"`
	Evidence      []Resolved       `json:"evidence"`
	Versions      []PackageVersion `json:"versions"`
}

type PackageResult struct {
	EvidenceCount int
	VersionCount  int
	Manifest      PackageManifest
}
