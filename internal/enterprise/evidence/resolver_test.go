package evidence

import (
	"testing"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

func TestResolveOrderedRejectsMissingEvidence(t *testing.T) {
	_, err := resolveOrdered([]string{"e1", "e2"}, []raw.Evidence{{ID: "e1", VersionID: "v1", ObjectKey: "raw/v1/a.pdf"}})
	if err == nil {
		t.Fatal("expected missing evidence error")
	}
}

func TestPackageVersionsDeduplicatesVersion(t *testing.T) {
	items := []Resolved{
		{EvidenceID: "e1", DocumentID: "d1", VersionID: "v1", ObjectKey: "raw/v1/a.pdf"},
		{EvidenceID: "e2", DocumentID: "d1", VersionID: "v1", ObjectKey: "raw/v1/a.pdf"},
		{EvidenceID: "e3", DocumentID: "d1", VersionID: "v2", ObjectKey: "raw/v2/a.pdf"},
	}
	versions := packageVersions(items)
	if len(versions) != 2 {
		t.Fatalf("got %d versions", len(versions))
	}
	if versions[0].ArchivePath != "sources/v1/a.pdf" || versions[1].ArchivePath != "sources/v2/a.pdf" {
		t.Fatalf("unexpected archive paths: %#v", versions)
	}
}
