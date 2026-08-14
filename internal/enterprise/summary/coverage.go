package summary

import (
	"context"
	"fmt"
	"strings"
)

func repairCoverage(ctx context.Context, template Template, source UnitSource, model CompilerModel, judge QualityJudge, facts []Fact, missing []MissingFactCandidate) ([]Fact, bool, int, []MissingFactCandidate, error) {
	focus := missingSections(missing)
	repairProposals, err := model.ExtractFacts(ctx, template, source, focus, missing)
	if err != nil {
		return nil, false, 0, nil, fmt.Errorf("summary local coverage repair: %w", err)
	}
	repairFacts, repairPass, err := GroundFacts(ctx, template, source, repairProposals, judge)
	if err != nil {
		return nil, false, 0, nil, err
	}
	facts = mergeFacts(facts, repairFacts)
	remaining, err := judge.CheckCoverage(ctx, template, source, passingFacts(facts))
	if err != nil {
		return nil, false, 0, nil, fmt.Errorf("summary coverage recheck: %w", err)
	}
	return facts, repairPass, len(repairFacts), normalizeMissing(template, source, remaining), nil
}

func normalizeMissing(template Template, source UnitSource, in []MissingFactCandidate) []MissingFactCandidate {
	blockSet := make(map[string]struct{}, len(source.Blocks))
	for _, b := range source.Blocks {
		blockSet[b.ID] = struct{}{}
	}
	out := make([]MissingFactCandidate, 0, len(in))
	seen := map[string]struct{}{}
	for _, m := range in {
		m.Section = strings.TrimSpace(m.Section)
		m.Description = strings.TrimSpace(m.Description)
		if m.Description == "" || !template.HasSection(m.Section) {
			continue
		}
		validBlocks := make([]string, 0, len(m.SourceBlockIDs))
		for _, blockID := range uniqueStrings(m.SourceBlockIDs) {
			if _, ok := blockSet[blockID]; ok {
				validBlocks = append(validBlocks, blockID)
			}
		}
		if len(validBlocks) == 0 {
			continue
		}
		m.SourceBlockIDs = validBlocks
		key := m.Section + "\x00" + normalizeSupportText(m.Description)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, m)
	}
	return out
}

func missingSections(missing []MissingFactCandidate) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(missing))
	for _, m := range missing {
		if _, ok := seen[m.Section]; ok {
			continue
		}
		seen[m.Section] = struct{}{}
		out = append(out, m.Section)
	}
	return out
}

func mergeFacts(a, b []Fact) []Fact {
	out := append([]Fact(nil), a...)
	seen := make(map[string]struct{}, len(a)+len(b))
	for _, f := range out {
		seen[f.Section+"\x00"+normalizeSupportText(f.FactText)] = struct{}{}
	}
	for _, f := range b {
		key := f.Section + "\x00" + normalizeSupportText(f.FactText)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, f)
	}
	return out
}
