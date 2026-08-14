package summary

import (
	"context"
	"fmt"
	"strings"
	"time"
)

// CompileUnit performs the Summary quality gate. The generator only proposes
// facts; a distinct judge evaluates semantic support and coverage.
func CompileUnit(ctx context.Context, source UnitSource, model CompilerModel, judge QualityJudge) (CompileResult, error) {
	if model == nil || judge == nil {
		return CompileResult{}, fmt.Errorf("summary compile: generator and independent judge are required")
	}
	generatorID := strings.TrimSpace(model.Identity())
	judgeID := strings.TrimSpace(judge.Identity())
	if generatorID == "" || judgeID == "" {
		return CompileResult{}, fmt.Errorf("summary compile: model identities are required")
	}
	if strings.EqualFold(generatorID, judgeID) {
		return CompileResult{}, fmt.Errorf("summary compile: generator and judge must be different models")
	}

	template := TemplateFor(source.Unit.DocumentType)
	proposed, err := model.ExtractFacts(ctx, template, source, nil, nil)
	if err != nil {
		return CompileResult{}, fmt.Errorf("summary extract facts: %w", err)
	}
	facts, groundingPass, err := GroundFacts(ctx, template, source, proposed, judge)
	if err != nil {
		return CompileResult{}, err
	}
	missing, err := judge.CheckCoverage(ctx, template, source, passingFacts(facts))
	if err != nil {
		return CompileResult{}, fmt.Errorf("summary coverage: %w", err)
	}
	missing = normalizeMissing(template, source, missing)

	repairs := 0
	if len(missing) > 0 {
		var repairPass bool
		facts, repairPass, repairs, missing, err = repairCoverage(ctx, template, source, model, judge, facts, missing)
		if err != nil {
			return CompileResult{}, err
		}
		groundingPass = groundingPass && repairPass
	}

	groundingStatus := "FAIL"
	if groundingPass && allFactsGrounded(facts) && len(facts) > 0 {
		groundingStatus = "PASS"
	}
	coverageStatus := "FAIL"
	if len(missing) == 0 {
		coverageStatus = "PASS"
	}

	markdown := RenderMarkdown(template, source, facts)
	summaryID := deterministicUUID("summary:" + source.Unit.ID + ":" + generatorID + ":" + contentHash(markdown))
	for i := range facts {
		facts[i].SummaryID = summaryID
	}
	stored := StoredSummary{
		ID: summaryID, UnitID: source.Unit.ID, VersionID: source.Unit.VersionID,
		GroundingStatus: groundingStatus, CoverageStatus: coverageStatus,
		Lifecycle: "INACTIVE", Model: generatorID, Facts: facts, CreatedAt: time.Now().UTC(),
	}
	if groundingStatus == "PASS" && coverageStatus == "PASS" {
		stored.Lifecycle = "ACTIVE"
	}
	return CompileResult{Summary: stored, Markdown: markdown, CoverageRepair: repairs}, nil
}

func passingFacts(facts []Fact) []Fact {
	out := make([]Fact, 0, len(facts))
	for _, f := range facts {
		if f.GroundingStatus == "PASS" {
			out = append(out, f)
		}
	}
	return out
}

func allFactsGrounded(facts []Fact) bool {
	for _, f := range facts {
		if f.GroundingStatus != "PASS" {
			return false
		}
	}
	return true
}
