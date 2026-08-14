package ask

import (
	"strings"
	"testing"

	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
)

func TestCitedIndexesOnlyAcceptsKnownSources(t *testing.T) {
	used := citedIndexes("First [1], repeated [1], second [2], invalid [9], malformed [x].", 2)
	if len(used) != 2 {
		t.Fatalf("used=%v", used)
	}
	if _, ok := used[1]; !ok {
		t.Fatal("citation 1 missing")
	}
	if _, ok := used[2]; !ok {
		t.Fatal("citation 2 missing")
	}
}

func TestValidateCitationsFailsClosed(t *testing.T) {
	if _, ok := validateCitations("No citations here.", 2); ok {
		t.Fatal("missing citations must fail")
	}
	if _, ok := validateCitations("Valid [1], invalid [9].", 2); ok {
		t.Fatal("out-of-range citation must fail")
	}
	used, ok := validateCitations("Supported [1] and [2].", 2)
	if !ok || len(used) != 2 {
		t.Fatalf("valid citations used=%v ok=%v", used, ok)
	}
}

func TestBuildSourcesDeduplicatesEvidence(t *testing.T) {
	evidence := enterpriseevidence.Resolved{EvidenceID: "ev-1", VersionID: "v-1", RawContent: "grounded"}
	results := []enterpriseapi.SearchKnowledgeResult{
		{ResultType: "RAW", Knowledge: "raw", Evidence: []enterpriseevidence.Resolved{evidence}},
		{ResultType: "SUMMARY", Knowledge: "summary", Evidence: []enterpriseevidence.Resolved{evidence}},
	}
	sources := buildSources(results)
	if len(sources) != 1 || sources[0].Index != 1 || sources[0].Evidence.EvidenceID != "ev-1" {
		t.Fatalf("sources=%+v", sources)
	}
}

func TestGroundedContextRespectsBudget(t *testing.T) {
	sources := []source{{Index: 1, ResultType: "RAW", Knowledge: strings.Repeat("k", 100), Evidence: enterpriseevidence.Resolved{EvidenceID: "ev", VersionID: "v", RawContent: strings.Repeat("r", 100)}}}
	if got := groundedContext(sources, 32); got != "" {
		t.Fatalf("expected source to be skipped when it exceeds budget, got=%q", got)
	}
	if got := groundedContext(sources, 1000); !strings.Contains(got, "[SOURCE 1]") || !strings.Contains(got, "Raw evidence") {
		t.Fatalf("unexpected context=%q", got)
	}
}
