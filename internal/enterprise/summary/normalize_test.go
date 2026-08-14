package summary

import (
	"testing"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

func TestNormalizeSingleUsesDocumentTypeAndFullRawRange(t *testing.T) {
	blocks := []raw.RawBlock{
		{ID: "b3", VersionID: "v1", BlockNo: 3},
		{ID: "b4", VersionID: "v1", BlockNo: 4},
		{ID: "b5", VersionID: "v1", BlockNo: 5},
	}
	units, err := NormalizeClassification("v1", "", blocks, Classification{DocumentType: DocumentTypeContract, StructureType: StructureSingle})
	if err != nil {
		t.Fatal(err)
	}
	if len(units) != 1 {
		t.Fatalf("expected one unit, got %#v", units)
	}
	got := units[0]
	if got.DocumentType != DocumentTypeContract || got.StructureType != StructureSingle || got.StartBlockNo != 3 || got.EndBlockNo != 5 {
		t.Fatalf("unexpected unit: %#v", got)
	}
}

func TestNormalizeBundleCreatesOrderedTypedUnits(t *testing.T) {
	blocks := []raw.RawBlock{{ID: "b0", VersionID: "v1", BlockNo: 0}, {ID: "b1", VersionID: "v1", BlockNo: 1}, {ID: "b2", VersionID: "v1", BlockNo: 2}, {ID: "b3", VersionID: "v1", BlockNo: 3}}
	units, err := NormalizeClassification("v1", "", blocks, Classification{StructureType: StructureBundle, Units: []UnitProposal{
		{Title: "Audit", DocumentType: DocumentTypeAuditReport, StartBlockNo: 2, EndBlockNo: 3},
		{Title: "Contract", DocumentType: DocumentTypeContract, StartBlockNo: 0, EndBlockNo: 1},
	}})
	if err != nil {
		t.Fatal(err)
	}
	if len(units) != 2 {
		t.Fatalf("expected two units, got %#v", units)
	}
	if units[0].Title != "Contract" || units[0].DocumentType != DocumentTypeContract || units[0].StartBlockNo != 0 || units[0].EndBlockNo != 1 {
		t.Fatalf("unexpected first unit: %#v", units[0])
	}
	if units[1].Title != "Audit" || units[1].DocumentType != DocumentTypeAuditReport || units[1].StartBlockNo != 2 || units[1].EndBlockNo != 3 {
		t.Fatalf("unexpected second unit: %#v", units[1])
	}
}

func TestNormalizeMixedAlwaysGeneralMixed(t *testing.T) {
	blocks := []raw.RawBlock{{ID: "b1", VersionID: "v1", BlockNo: 0}, {ID: "b2", VersionID: "v1", BlockNo: 1}}
	units, err := NormalizeClassification("v1", "", blocks, Classification{DocumentType: DocumentTypeContract, StructureType: StructureMixed})
	if err != nil {
		t.Fatal(err)
	}
	if len(units) != 1 || units[0].DocumentType != DocumentTypeGeneralMixed || units[0].StructureType != StructureMixed {
		t.Fatalf("unexpected units: %#v", units)
	}
}

func TestNormalizeBundleRejectsOverlap(t *testing.T) {
	blocks := []raw.RawBlock{{ID: "b1", VersionID: "v1", BlockNo: 0}, {ID: "b2", VersionID: "v1", BlockNo: 1}, {ID: "b3", VersionID: "v1", BlockNo: 2}}
	_, err := NormalizeClassification("v1", "", blocks, Classification{StructureType: StructureBundle, Units: []UnitProposal{
		{DocumentType: DocumentTypeContract, StartBlockNo: 0, EndBlockNo: 1},
		{DocumentType: DocumentTypeAuditReport, StartBlockNo: 1, EndBlockNo: 2},
	}})
	if err == nil {
		t.Fatal("expected overlap error")
	}
}

func TestUnitSourceRejectsCrossVersionEvidence(t *testing.T) {
	unit := DocumentUnit{ID: "u1", VersionID: "v1", StartBlockNo: 0, EndBlockNo: 0}
	blocks := []raw.RawBlock{{ID: "b1", VersionID: "v1", BlockNo: 0, Content: "source"}}
	evidence := []raw.Evidence{{ID: "e1", VersionID: "v2", BlockID: "b1", Content: "wrong version"}}
	source, err := UnitSourceFromRaw(unit, blocks, evidence)
	if err != nil {
		t.Fatal(err)
	}
	if len(source.EvidenceByBlock) != 0 {
		t.Fatalf("cross-version evidence leaked into unit: %#v", source.EvidenceByBlock)
	}
}
