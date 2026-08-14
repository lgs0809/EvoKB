package evaluation

import "testing"

func TestEvaluateCaseComputesArtifactMetrics(t *testing.T) {
	golden := ArtifactGoldenCase{
		ID:         "case-1",
		VersionIDs: []string{"v1"},
		ExpectedSummaryFacts: []TextExpectation{
			{Text: "Revenue was 100 million yuan", Alternatives: []string{"Revenue reached 100 million yuan"}},
			{Text: "Gross margin was 30 percent"},
		},
		ExpectedRelations: []RelationExpectation{
			{Source: "Acme Holdings", SourceAliases: []string{"ACME"}, RelationType: "CONTROLS", Target: "Acme Tech"},
			{Source: "Acme Tech", RelationType: "LOCATED_IN", Target: "Harbin"},
		},
		ExpectedEntityMappings: []EntityMappingExpectation{
			{SurfaceForm: "ACME Co.", EntityType: "COMPANY", CanonicalName: "Acme Holdings", CanonicalAliases: []string{"ACME Holdings Ltd"}},
		},
		ExpectedWikiClaims: []TextExpectation{
			{Text: "Acme Tech is controlled by Acme Holdings"},
			{Text: "Acme Tech is based in Harbin"},
		},
	}
	actual := ActualArtifacts{
		SummaryFacts: []string{"Revenue reached   100 million yuan", "Unexpected fact"},
		Relations: []RelationActual{
			{Source: "ACME", RelationType: "controls", Target: "Acme Tech"},
			{Source: "Acme Tech", RelationType: "SELLS_TO", Target: "Other Co"},
		},
		EntityMappings: []EntityMappingActual{{SurfaceForm: "ACME Co.", EntityType: "company", CanonicalName: "ACME Holdings Ltd"}},
		WikiClaims: []WikiClaimActual{
			{Text: "Acme Tech is controlled by Acme Holdings", Grounded: true},
			{Text: "Unsupported wiki claim", Grounded: false},
		},
	}

	result := EvaluateCase(golden, actual)
	assertFloat(t, result.SummaryFact.Precision, 0.5)
	assertFloat(t, result.SummaryFact.Recall, 0.5)
	assertFloat(t, result.GraphRelation.Precision, 0.5)
	assertFloat(t, result.GraphRelation.Recall, 0.5)
	assertFloat(t, result.EntityResolution.Accuracy, 1)
	assertFloat(t, result.WikiClaim.Precision, 0.5)
	assertFloat(t, result.WikiClaim.Recall, 0.5)
	assertFloat(t, result.WikiClaimGrounding.Rate, 0.5)
}

func TestEvaluateCaseLeavesMetricsUnavailableWithoutGold(t *testing.T) {
	result := EvaluateCase(ArtifactGoldenCase{ID: "empty", VersionIDs: []string{"v1"}}, ActualArtifacts{})
	if result.SummaryFact.Available || result.SummaryFact.Precision != nil || result.SummaryFact.Recall != nil {
		t.Fatalf("summary metric should be unavailable: %#v", result.SummaryFact)
	}
	if result.GraphRelation.Available || result.GraphRelation.Precision != nil || result.GraphRelation.Recall != nil {
		t.Fatalf("graph metric should be unavailable: %#v", result.GraphRelation)
	}
	if result.EntityResolution.Available || result.EntityResolution.Accuracy != nil {
		t.Fatalf("entity metric should be unavailable: %#v", result.EntityResolution)
	}
	if result.WikiClaim.Available || result.WikiClaim.Precision != nil || result.WikiClaim.Recall != nil {
		t.Fatalf("wiki metric should be unavailable: %#v", result.WikiClaim)
	}
	if result.WikiClaimGrounding.Available || result.WikiClaimGrounding.Rate != nil {
		t.Fatalf("wiki grounding should be unavailable: %#v", result.WikiClaimGrounding)
	}
}

func TestBuildReportUsesMicroAveraging(t *testing.T) {
	one := EvaluateCase(
		ArtifactGoldenCase{ID: "one", VersionIDs: []string{"v1"}, ExpectedSummaryFacts: []TextExpectation{{Text: "a"}}},
		ActualArtifacts{SummaryFacts: []string{"a"}},
	)
	two := EvaluateCase(
		ArtifactGoldenCase{ID: "two", VersionIDs: []string{"v2"}, ExpectedSummaryFacts: []TextExpectation{{Text: "b"}, {Text: "c"}, {Text: "d"}}},
		ActualArtifacts{SummaryFacts: []string{"b", "extra-1", "extra-2"}},
	)
	report := BuildReport([]ArtifactCaseResult{one, two})
	if report.SummaryFact.Matched != 2 || report.SummaryFact.Actual != 4 || report.SummaryFact.Expected != 4 {
		t.Fatalf("unexpected aggregate counts: %#v", report.SummaryFact)
	}
	assertFloat(t, report.SummaryFact.Precision, 0.5)
	assertFloat(t, report.SummaryFact.Recall, 0.5)
	if report.TaskSuccessAvailable || report.RetrievalRoundsAvailable {
		t.Fatal("artifact evaluator must not claim agent-only metrics")
	}
}

func TestVersionClauseDeduplicatesAndRejectsEmpty(t *testing.T) {
	clause, args, err := versionClause([]string{" v1 ", "v1", "v2"})
	if err != nil {
		t.Fatal(err)
	}
	if clause != "?,?" || len(args) != 2 || args[0] != "v1" || args[1] != "v2" {
		t.Fatalf("unexpected clause=%q args=%#v", clause, args)
	}
	if _, _, err := versionClause([]string{"", "  "}); err == nil {
		t.Fatal("expected empty version scope to fail")
	}
}

func assertFloat(t *testing.T, got *float64, want float64) {
	t.Helper()
	if got == nil {
		t.Fatalf("got nil want %v", want)
	}
	if diff := *got - want; diff < -1e-9 || diff > 1e-9 {
		t.Fatalf("got %v want %v", *got, want)
	}
}
