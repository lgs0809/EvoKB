package summary

import (
	"context"
	"fmt"
	"strings"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

// GroundFacts maps every proposed fact to exact Raw evidence from the same
// active document version. Critical atoms are checked deterministically before
// any semantic judge is allowed to pass a fact.
func GroundFacts(ctx context.Context, template Template, source UnitSource, proposed []ProposedFact, judge QualityJudge) ([]Fact, bool, error) {
	facts := make([]Fact, 0, len(proposed))
	allPass := true
	seenFacts := map[string]struct{}{}
	for i, p := range proposed {
		p.Section = strings.TrimSpace(p.Section)
		p.FactText = strings.TrimSpace(p.FactText)
		if p.FactText == "" || !template.HasSection(p.Section) {
			allPass = false
			continue
		}
		factKey := p.Section + "\x00" + normalizeSupportText(p.FactText)
		if _, exists := seenFacts[factKey]; exists {
			continue
		}
		seenFacts[factKey] = struct{}{}

		fact := Fact{
			ID: deterministicUUID(fmt.Sprintf("fact:%s:%s:%d:%s", source.Unit.ID, p.Section, i, p.FactText)),
			Section: p.Section, FactText: p.FactText, GroundingStatus: "FAIL",
		}
		blockIDs := uniqueStrings(p.EvidenceBlockIDs)
		if len(blockIDs) == 0 {
			fact.GroundingReason = "no evidence_block_ids"
			facts = append(facts, fact)
			allPass = false
			continue
		}

		evidence := make([]raw.Evidence, 0, len(blockIDs))
		for _, blockID := range blockIDs {
			ev, ok := source.EvidenceByBlock[blockID]
			if !ok || ev.VersionID != source.Unit.VersionID {
				fact.GroundingReason = "unresolvable raw block " + blockID
				evidence = nil
				break
			}
			fact.EvidenceBlockIDs = append(fact.EvidenceBlockIDs, blockID)
			fact.EvidenceIDs = append(fact.EvidenceIDs, ev.ID)
			evidence = append(evidence, ev)
		}
		if len(evidence) != len(blockIDs) {
			facts = append(facts, fact)
			allPass = false
			continue
		}

		support := evidenceSupportText(evidence)
		unsupported := unsupportedCriticalAtoms(p.FactText, support)
		if len(unsupported) > 0 {
			fact.GroundingReason = "unsupported critical atoms: " + strings.Join(unsupported, ", ")
			facts = append(facts, fact)
			allPass = false
			continue
		}

		if strings.Contains(normalizeSupportText(support), normalizeSupportText(p.FactText)) {
			fact.GroundingStatus = "PASS"
			fact.GroundingReason = "exact raw support"
			facts = append(facts, fact)
			continue
		}
		if judge == nil {
			fact.GroundingReason = "semantic judge required"
			facts = append(facts, fact)
			allPass = false
			continue
		}
		ok, reason, err := judge.SupportsFact(ctx, fact, evidence)
		if err != nil {
			return nil, false, fmt.Errorf("summary grounding judge: %w", err)
		}
		fact.GroundingReason = strings.TrimSpace(reason)
		if ok {
			fact.GroundingStatus = "PASS"
		} else {
			allPass = false
		}
		facts = append(facts, fact)
	}
	return facts, allPass, nil
}

func evidenceSupportText(evidence []raw.Evidence) string {
	parts := make([]string, 0, len(evidence))
	for _, ev := range evidence {
		parts = append(parts, ev.Content)
	}
	return strings.Join(parts, "\n")
}

func uniqueStrings(in []string) []string {
	seen := make(map[string]struct{}, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	return out
}
