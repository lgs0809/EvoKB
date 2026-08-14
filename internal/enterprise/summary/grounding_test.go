package summary

import (
	"context"
	"testing"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type testJudge struct{}

func (testJudge) Identity() string { return "independent-judge" }
func (testJudge) SupportsFact(context.Context, Fact, []raw.Evidence) (bool, string, error) {
	return true, "supported", nil
}
func (testJudge) CheckCoverage(context.Context, Template, UnitSource, []Fact) ([]MissingFactCandidate, error) {
	return nil, nil
}

func TestGroundFactsExactRawSupport(t *testing.T) {
	source := UnitSource{
		Unit: DocumentUnit{ID: "u1", VersionID: "v1", DocumentType: DocumentTypeProject},
		EvidenceByBlock: map[string]raw.Evidence{
			"b1": {ID: "e1", VersionID: "v1", BlockID: "b1", Content: "项目范围包括数据接入、指标分析和报告输出。"},
		},
	}
	facts, pass, err := GroundFacts(context.Background(), TemplateFor(DocumentTypeProject), source, []ProposedFact{{
		Section: "项目与主体", FactText: "项目范围包括数据接入、指标分析和报告输出。", EvidenceBlockIDs: []string{"b1"},
	}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !pass || len(facts) != 1 || facts[0].GroundingStatus != "PASS" || len(facts[0].EvidenceIDs) != 1 || facts[0].EvidenceIDs[0] != "e1" {
		t.Fatalf("unexpected grounding: pass=%v facts=%#v", pass, facts)
	}
}

func TestGroundFactsSemanticParaphraseNeedsJudge(t *testing.T) {
	source := UnitSource{
		Unit: DocumentUnit{ID: "u1", VersionID: "v1", DocumentType: DocumentTypeProject},
		EvidenceByBlock: map[string]raw.Evidence{
			"b1": {ID: "e1", VersionID: "v1", BlockID: "b1", Content: "核心部件目前仅由供应商A提供。"},
		},
	}
	proposal := []ProposedFact{{Section: "重要问题与风险", FactText: "核心部件存在单一供应来源风险。", EvidenceBlockIDs: []string{"b1"}}}
	facts, pass, err := GroundFacts(context.Background(), TemplateFor(DocumentTypeProject), source, proposal, nil)
	if err != nil {
		t.Fatal(err)
	}
	if pass || facts[0].GroundingReason != "semantic judge required" {
		t.Fatalf("unexpected no-judge grounding: pass=%v facts=%#v", pass, facts)
	}
	facts, pass, err = GroundFacts(context.Background(), TemplateFor(DocumentTypeProject), source, proposal, testJudge{})
	if err != nil || !pass || facts[0].GroundingStatus != "PASS" {
		t.Fatalf("unexpected judged grounding: pass=%v err=%v facts=%#v", pass, err, facts)
	}
}
