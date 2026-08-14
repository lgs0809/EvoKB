package graph

import (
	"strings"
	"testing"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

func TestValidateExtractionFailsCoverageWhenEligibleFactHasNoRelation(t *testing.T) {
	facts := map[string]enterprisesummary.Fact{
		"f1": {ID: "f1", EvidenceIDs: []string{"e1"}, GroundingStatus: "PASS"},
		"f2": {ID: "f2", EvidenceIDs: []string{"e2"}, GroundingStatus: "PASS"},
	}
	extraction := Extraction{
		FactDecisions: []FactDecision{
			{FactID: "f1", GraphEligible: true},
			{FactID: "f2", GraphEligible: true},
		},
		Relations: []ProposedRelation{{
			SourceName: "Acme", SourceType: EntityCompany, RelationType: RelationControls,
			TargetName: "Target", TargetType: EntityCompany, SupportingFactIDs: []string{"f1"}, Confidence: 0.9,
		}},
	}
	coverage, relations, err := validateExtraction(facts, extraction)
	if err != nil {
		t.Fatal(err)
	}
	if coverage.Pass || coverage.EligibleFacts != 2 || coverage.CoveredFacts != 1 || len(coverage.MissingFacts) != 1 || coverage.MissingFacts[0] != "f2" {
		t.Fatalf("unexpected coverage: %#v", coverage)
	}
	if len(relations) != 1 {
		t.Fatalf("expected valid relation to be retained, got %#v", relations)
	}
}

func TestValidateExtractionRejectsRelationWithoutRawEvidence(t *testing.T) {
	facts := map[string]enterprisesummary.Fact{
		"f1": {ID: "f1", GroundingStatus: "PASS"},
	}
	_, _, err := validateExtraction(facts, Extraction{
		FactDecisions: []FactDecision{{FactID: "f1", GraphEligible: true}},
		Relations: []ProposedRelation{{
			SourceName: "Acme", SourceType: EntityCompany, RelationType: RelationControls,
			TargetName: "Target", TargetType: EntityCompany, SupportingFactIDs: []string{"f1"}, Confidence: 0.9,
		}},
	})
	if err == nil || !strings.Contains(err.Error(), "without raw evidence") {
		t.Fatalf("expected raw evidence rejection, got %v", err)
	}
}

func TestValidateExtractionAllowsExplicitlySkippedNonEligibleFact(t *testing.T) {
	facts := map[string]enterprisesummary.Fact{
		"f1": {ID: "f1", EvidenceIDs: []string{"e1"}, GroundingStatus: "PASS"},
	}
	coverage, relations, err := validateExtraction(facts, Extraction{
		FactDecisions: []FactDecision{{FactID: "f1", GraphEligible: false, SkipReason: "descriptive fact has no graph value"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !coverage.Pass || coverage.SkippedFacts != 1 || coverage.EligibleFacts != 0 || coverage.CoveredFacts != 0 || len(relations) != 0 {
		t.Fatalf("unexpected coverage=%#v relations=%#v", coverage, relations)
	}
}

func TestValidateExtractionRequiresDecisionForEveryGroundedFact(t *testing.T) {
	facts := map[string]enterprisesummary.Fact{
		"f1": {ID: "f1", EvidenceIDs: []string{"e1"}, GroundingStatus: "PASS"},
	}
	_, _, err := validateExtraction(facts, Extraction{})
	if err == nil || !strings.Contains(err.Error(), "missing decision") {
		t.Fatalf("expected missing decision rejection, got %v", err)
	}
}
