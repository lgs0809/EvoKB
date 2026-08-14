package retrieval

import "testing"

func TestBalancedRerankPoolPreservesLayerDiversity(t *testing.T) {
	var input []Candidate
	for i := 0; i < 10; i++ {
		input = append(input, Candidate{ID: "raw" + string(rune('a'+i)), ResultType: TypeRaw})
	}
	input = append(input,
		Candidate{ID: "summary", ResultType: TypeSummary},
		Candidate{ID: "wiki", ResultType: TypeWiki},
		Candidate{ID: "graph", ResultType: TypeGraph},
	)
	got := balancedRerankPool(input, 4)
	if len(got) != 4 {
		t.Fatalf("got %d candidates", len(got))
	}
	seen := map[string]bool{}
	for _, candidate := range got {
		seen[candidate.ResultType] = true
	}
	for _, kind := range []string{TypeRaw, TypeSummary, TypeWiki, TypeGraph} {
		if !seen[kind] {
			t.Fatalf("missing layer %s in balanced pool: %#v", kind, got)
		}
	}
}
