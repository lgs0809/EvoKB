package summary

import (
	"crypto/sha256"
	"fmt"
	"sort"
	"strings"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

// NormalizeClassification turns the model's classification into deterministic
// document units. Model output is advisory; all boundaries and vocabularies are
// checked here before anything can be persisted or compiled.
func NormalizeClassification(versionID, manualDocumentType string, blocks []raw.RawBlock, c Classification) ([]DocumentUnit, error) {
	if len(blocks) == 0 {
		return nil, fmt.Errorf("summary classification: no raw blocks")
	}
	minBlock, maxBlock := blocks[0].BlockNo, blocks[0].BlockNo
	available := make(map[int]struct{}, len(blocks))
	for _, b := range blocks {
		available[b.BlockNo] = struct{}{}
		if b.BlockNo < minBlock {
			minBlock = b.BlockNo
		}
		if b.BlockNo > maxBlock {
			maxBlock = b.BlockNo
		}
	}

	manualDocumentType = strings.TrimSpace(manualDocumentType)
	if manualDocumentType != "" && !IsAllowedDocumentType(manualDocumentType) {
		return nil, fmt.Errorf("summary classification: unsupported manual document type %q", manualDocumentType)
	}

	structure := strings.ToLower(strings.TrimSpace(c.StructureType))
	switch structure {
	case StructureSingle, StructureBundle, StructureMixed:
	default:
		return nil, fmt.Errorf("summary classification: unsupported structure type %q", c.StructureType)
	}

	if structure == StructureMixed {
		return []DocumentUnit{newUnit(versionID, 1, "", StructureMixed, DocumentTypeGeneralMixed, minBlock, maxBlock)}, nil
	}

	if structure == StructureSingle {
		docType := strings.TrimSpace(c.DocumentType)
		if manualDocumentType != "" {
			docType = manualDocumentType
		}
		if !IsAllowedDocumentType(docType) {
			docType = DocumentTypeGeneralMixed
		}
		return []DocumentUnit{newUnit(versionID, 1, "", StructureSingle, docType, minBlock, maxBlock)}, nil
	}

	if len(c.Units) == 0 {
		return nil, fmt.Errorf("summary classification: bundle has no units")
	}
	proposals := append([]UnitProposal(nil), c.Units...)
	sort.SliceStable(proposals, func(i, j int) bool {
		if proposals[i].StartBlockNo == proposals[j].StartBlockNo {
			return proposals[i].EndBlockNo < proposals[j].EndBlockNo
		}
		return proposals[i].StartBlockNo < proposals[j].StartBlockNo
	})

	units := make([]DocumentUnit, 0, len(proposals))
	lastEnd := minBlock - 1
	for i, p := range proposals {
		if p.StartBlockNo > p.EndBlockNo {
			return nil, fmt.Errorf("summary classification: unit %d has inverted range %d-%d", i+1, p.StartBlockNo, p.EndBlockNo)
		}
		if p.StartBlockNo < minBlock || p.EndBlockNo > maxBlock {
			return nil, fmt.Errorf("summary classification: unit %d range %d-%d outside raw range %d-%d", i+1, p.StartBlockNo, p.EndBlockNo, minBlock, maxBlock)
		}
		if p.StartBlockNo <= lastEnd {
			return nil, fmt.Errorf("summary classification: bundle units overlap at block %d", p.StartBlockNo)
		}
		for n := p.StartBlockNo; n <= p.EndBlockNo; n++ {
			if _, ok := available[n]; !ok {
				return nil, fmt.Errorf("summary classification: unit %d references missing raw block %d", i+1, n)
			}
		}
		docType := strings.TrimSpace(p.DocumentType)
		if manualDocumentType != "" {
			docType = manualDocumentType
		}
		if !IsAllowedDocumentType(docType) {
			docType = DocumentTypeGeneralMixed
		}
		units = append(units, newUnit(versionID, i+1, strings.TrimSpace(p.Title), StructureBundle, docType, p.StartBlockNo, p.EndBlockNo))
		lastEnd = p.EndBlockNo
	}
	return units, nil
}

func newUnit(versionID string, unitNo int, title, structureType, documentType string, start, end int) DocumentUnit {
	seed := fmt.Sprintf("unit:%s:%d:%d:%d:%s", versionID, unitNo, start, end, documentType)
	return DocumentUnit{
		ID: deterministicUUID(seed), VersionID: versionID, UnitNo: unitNo, Title: title,
		StructureType: structureType, DocumentType: documentType, StartBlockNo: start, EndBlockNo: end,
	}
}

func deterministicUUID(seed string) string {
	h := sha256.Sum256([]byte(seed))
	b := h[:16]
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// UnitSourceFromRaw selects exact raw blocks and evidence for one normalized
// unit. The caller supplies only evidence from the same active version.
func UnitSourceFromRaw(unit DocumentUnit, blocks []raw.RawBlock, evidence []raw.Evidence) (UnitSource, error) {
	s := UnitSource{Unit: unit, EvidenceByBlock: map[string]raw.Evidence{}}
	for _, b := range blocks {
		if b.VersionID != unit.VersionID {
			continue
		}
		if b.BlockNo >= unit.StartBlockNo && b.BlockNo <= unit.EndBlockNo {
			s.Blocks = append(s.Blocks, b)
		}
	}
	if len(s.Blocks) == 0 {
		return UnitSource{}, fmt.Errorf("summary unit %s has no raw blocks", unit.ID)
	}
	blockIDs := make(map[string]struct{}, len(s.Blocks))
	for _, b := range s.Blocks {
		blockIDs[b.ID] = struct{}{}
	}
	for _, ev := range evidence {
		if ev.VersionID != unit.VersionID {
			continue
		}
		if _, ok := blockIDs[ev.BlockID]; ok {
			s.EvidenceByBlock[ev.BlockID] = ev
		}
	}
	return s, nil
}
