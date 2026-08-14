package retrieval

import (
	"context"
	"testing"

	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type testLane struct {
	kind  string
	items []Candidate
}

func (l *testLane) Type() string { return l.kind }
func (l *testLane) Search(context.Context, string, raw.SearchFilters, int) ([]Candidate, error) {
	return l.items, nil
}

type testReranker struct{ calls int }

func (r *testReranker) Rerank(_ context.Context, _ string, docs []string) ([]float64, error) {
	r.calls++
	out := make([]float64, len(docs))
	for i := range docs {
		out[i] = float64(len(docs) - i)
	}
	return out, nil
}

type testResolver struct{}

func (testResolver) Resolve(_ context.Context, ids []string) ([]enterpriseevidence.Resolved, error) {
	if len(ids) == 0 || ids[0] == "unresolvable" {
		return nil, nil
	}
	return []enterpriseevidence.Resolved{{EvidenceID: ids[0], VersionID: "v1", ObjectKey: "raw/v1/source.pdf"}}, nil
}

func TestSearchReranksOnceAndDropsUnresolvableCandidates(t *testing.T) {
	reranker := &testReranker{}
	service := &Service{
		RawLane: &testLane{kind: TypeRaw, items: []Candidate{{ID: "raw", Knowledge: "raw", EvidenceIDs: []string{"e1"}}}},
		WikiLane: &testLane{kind: TypeWiki, items: []Candidate{{ID: "wiki", Knowledge: "wiki", EvidenceIDs: []string{"unresolvable"}}}},
		Reranker: reranker,
		Resolver: testResolver{},
	}
	result, err := service.Search(context.Background(), "query", raw.SearchFilters{}, 10)
	if err != nil {
		t.Fatal(err)
	}
	if reranker.calls != 1 {
		t.Fatalf("expected one unified rerank, got %d", reranker.calls)
	}
	if len(result.Results) != 1 || result.Results[0].Candidate.ID != "raw" {
		t.Fatalf("unexpected evidence-gated results: %#v", result.Results)
	}
}
