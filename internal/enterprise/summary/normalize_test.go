package summary

import (
	"testing"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

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
