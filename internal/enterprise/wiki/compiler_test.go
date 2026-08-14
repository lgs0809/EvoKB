package wiki

import (
	"bytes"
	"context"
	"io"
	"strings"
	"testing"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

type testWikiStore struct {
	summaries []enterprisesummary.StoredSummary
	saved     []StoredWiki
}

func (s *testWikiStore) LoadSummaries(context.Context, []string) ([]enterprisesummary.StoredSummary, error) {
	return append([]enterprisesummary.StoredSummary(nil), s.summaries...), nil
}
func (s *testWikiStore) NextWikiVersion(context.Context, string) (uint64, error) { return 1, nil }
func (s *testWikiStore) SaveWiki(_ context.Context, wiki StoredWiki) error {
	s.saved = append(s.saved, wiki)
	return nil
}

type testWikiObjectStore struct{ objects map[string][]byte }

func (s *testWikiObjectStore) Put(_ context.Context, key string, r io.Reader, _ int64, _ string) error {
	if s.objects == nil {
		s.objects = map[string][]byte{}
	}
	b, err := io.ReadAll(r)
	if err != nil {
		return err
	}
	s.objects[key] = b
	return nil
}

type testWikiGenerator struct{ proposal Proposal }

func (g testWikiGenerator) Identity() string { return "writer-test" }
func (g testWikiGenerator) Generate(context.Context, Template, SourceSet) (Proposal, error) {
	return g.proposal, nil
}

type testWikiJudge struct{ supported bool }

func (j testWikiJudge) Identity() string { return "judge-test" }
func (j testWikiJudge) SupportsClaim(context.Context, ProposedClaim, []enterprisesummary.Fact) (bool, string, error) {
	if j.supported {
		return true, "supported", nil
	}
	return false, "rejected", nil
}

func eligibleSummary(id string, facts ...enterprisesummary.Fact) enterprisesummary.StoredSummary {
	return enterprisesummary.StoredSummary{
		ID: id, Lifecycle: "ACTIVE", GroundingStatus: "PASS", CoverageStatus: "PASS", Facts: facts,
	}
}

func groundedFact(id, text string, evidence ...string) enterprisesummary.Fact {
	return enterprisesummary.Fact{ID: id, FactText: text, GroundingStatus: "PASS", EvidenceIDs: evidence}
}

func newTestCompiler(store *testWikiStore, proposal Proposal, supported bool) *Compiler {
	return &Compiler{
		Store: store,
		Objects: &testWikiObjectStore{},
		Generator: testWikiGenerator{proposal: proposal},
		Judge: testWikiJudge{supported: supported},
	}
}

func TestCompilerRejectsUncoveredEligibleFact(t *testing.T) {
	store := &testWikiStore{summaries: []enterprisesummary.StoredSummary{eligibleSummary("s1",
		groundedFact("f1", "Company A exists", "e1"),
		groundedFact("f2", "Company A has a board", "e2"),
	)}}
	compiler := newTestCompiler(store, Proposal{
		WikiType: TypeCompany,
		FactDecisions: []FactDecision{{FactID: "f1", WikiEligible: true}, {FactID: "f2", WikiEligible: true}},
		Claims: []ProposedClaim{{Section: "Profile", ClaimText: "Company A exists", SupportingFactIDs: []string{"f1"}}},
	}, true)

	_, err := compiler.Compile(context.Background(), CompileRequest{WikiType: TypeCompany, Title: "Company A", SummaryIDs: []string{"s1"}})
	if err == nil || !strings.Contains(err.Error(), "coverage failed") {
		t.Fatalf("expected coverage failure, got %v", err)
	}
	if len(store.saved) != 0 {
		t.Fatalf("wiki must not become ACTIVE on coverage failure")
	}
}

func TestCompilerRejectsSourceFactWithoutRawEvidence(t *testing.T) {
	store := &testWikiStore{summaries: []enterprisesummary.StoredSummary{eligibleSummary("s1",
		groundedFact("f1", "Company A exists"),
	)}}
	compiler := newTestCompiler(store, Proposal{}, true)

	_, err := compiler.Compile(context.Background(), CompileRequest{WikiType: TypeCompany, Title: "Company A", SummaryIDs: []string{"s1"}})
	if err == nil || !strings.Contains(err.Error(), "has no raw evidence") {
		t.Fatalf("expected raw evidence failure, got %v", err)
	}
}

func TestCompilerRejectsIndependentJudgeFailure(t *testing.T) {
	store := &testWikiStore{summaries: []enterprisesummary.StoredSummary{eligibleSummary("s1",
		groundedFact("f1", "Company A exists", "e1"),
	)}}
	compiler := newTestCompiler(store, Proposal{
		WikiType: TypeCompany,
		FactDecisions: []FactDecision{{FactID: "f1", WikiEligible: true}},
		Claims: []ProposedClaim{{Section: "Profile", ClaimText: "Company A exists", SupportingFactIDs: []string{"f1"}}},
	}, false)

	_, err := compiler.Compile(context.Background(), CompileRequest{WikiType: TypeCompany, Title: "Company A", SummaryIDs: []string{"s1"}})
	if err == nil || !strings.Contains(err.Error(), "not grounded") {
		t.Fatalf("expected judge rejection, got %v", err)
	}
	if len(store.saved) != 0 {
		t.Fatalf("wiki must not become ACTIVE after judge rejection")
	}
}

func TestCompilerUnionsEvidenceAcrossSourceFacts(t *testing.T) {
	store := &testWikiStore{summaries: []enterprisesummary.StoredSummary{
		eligibleSummary("s1", groundedFact("f1", "Company A signed the agreement", "e1")),
		eligibleSummary("s2", groundedFact("f2", "Company B signed the agreement", "e2", "e3")),
	}}
	objects := &testWikiObjectStore{}
	compiler := &Compiler{
		Store: store, Objects: objects,
		Generator: testWikiGenerator{proposal: Proposal{
			WikiType: TypeCompany,
			FactDecisions: []FactDecision{{FactID: "f1", WikiEligible: true}, {FactID: "f2", WikiEligible: true}},
			Claims: []ProposedClaim{{Section: "Material Matters", ClaimText: "Company A and Company B signed the agreement", SupportingFactIDs: []string{"f1", "f2"}}},
		}},
		Judge: testWikiJudge{supported: true},
	}

	result, err := compiler.Compile(context.Background(), CompileRequest{WikiType: TypeCompany, Title: "Agreement participants", SummaryIDs: []string{"s1", "s2"}})
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	if len(result.Wiki.Claims) != 1 {
		t.Fatalf("expected one claim, got %d", len(result.Wiki.Claims))
	}
	claim := result.Wiki.Claims[0]
	want := []string{"e1", "e2", "e3"}
	if len(claim.EvidenceIDs) != len(want) {
		t.Fatalf("evidence union = %v, want %v", claim.EvidenceIDs, want)
	}
	for i := range want {
		if claim.EvidenceIDs[i] != want[i] {
			t.Fatalf("evidence union = %v, want %v", claim.EvidenceIDs, want)
		}
	}
	if len(store.saved) != 1 || store.saved[0].Lifecycle != "ACTIVE" {
		t.Fatalf("expected exactly one ACTIVE wiki save")
	}
	if got := objects.objects[result.Wiki.ObjectKey]; len(got) == 0 || !bytes.Equal(got, []byte(result.Markdown)) {
		t.Fatalf("wiki markdown object was not published before metadata visibility")
	}
}
