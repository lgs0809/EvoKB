package evaluation

import "strings"

// EvaluateCase compares persisted actual artifacts with one human-authored
// Golden case. Matching is deterministic and one-to-one; no LLM is used by the
// evaluator itself.
func EvaluateCase(golden ArtifactGoldenCase, actual ActualArtifacts) ArtifactCaseResult {
	result := ArtifactCaseResult{ID: golden.ID, VersionIDs: append([]string(nil), golden.VersionIDs...)}

	summaryActual := uniqueTexts(actual.SummaryFacts)
	summaryMatched := matchTexts(summaryActual, golden.ExpectedSummaryFacts)
	result.SummaryFact = precisionRecall(summaryMatched, len(summaryActual), len(golden.ExpectedSummaryFacts))

	relationActual := uniqueRelations(actual.Relations)
	relationMatched := matchRelations(relationActual, golden.ExpectedRelations)
	result.GraphRelation = precisionRecall(relationMatched, len(relationActual), len(golden.ExpectedRelations))

	mappingActual := uniqueMappings(actual.EntityMappings)
	mappingMatched := matchMappings(mappingActual, golden.ExpectedEntityMappings)
	result.EntityResolution = accuracy(mappingMatched, len(golden.ExpectedEntityMappings))

	wikiTexts := make([]string, 0, len(actual.WikiClaims))
	grounded := 0
	for _, claim := range actual.WikiClaims {
		wikiTexts = append(wikiTexts, claim.Text)
		if claim.Grounded {
			grounded++
		}
	}
	wikiActual := uniqueTexts(wikiTexts)
	wikiMatched := matchTexts(wikiActual, golden.ExpectedWikiClaims)
	result.WikiClaim = precisionRecall(wikiMatched, len(wikiActual), len(golden.ExpectedWikiClaims))
	result.WikiClaimGrounding = rate(grounded, len(actual.WikiClaims))
	return result
}

// BuildReport aggregates counts across cases. Precision/recall aggregation is
// micro-averaged so a one-item case does not carry the same weight as a
// twenty-item case. Metrics without Golden expectations remain unavailable.
func BuildReport(results []ArtifactCaseResult) ArtifactEvalReport {
	report := ArtifactEvalReport{
		Cases:                    len(results),
		TaskSuccessAvailable:     false,
		RetrievalRoundsAvailable: false,
		Results:                  append([]ArtifactCaseResult(nil), results...),
	}
	for _, result := range results {
		if result.SummaryFact.Available {
			report.SummaryFact.Available = true
			report.SummaryFact.Matched += result.SummaryFact.Matched
			report.SummaryFact.Actual += result.SummaryFact.Actual
			report.SummaryFact.Expected += result.SummaryFact.Expected
		}
		if result.GraphRelation.Available {
			report.GraphRelation.Available = true
			report.GraphRelation.Matched += result.GraphRelation.Matched
			report.GraphRelation.Actual += result.GraphRelation.Actual
			report.GraphRelation.Expected += result.GraphRelation.Expected
		}
		if result.EntityResolution.Available {
			report.EntityResolution.Available = true
			report.EntityResolution.Matched += result.EntityResolution.Matched
			report.EntityResolution.Expected += result.EntityResolution.Expected
		}
		if result.WikiClaim.Available {
			report.WikiClaim.Available = true
			report.WikiClaim.Matched += result.WikiClaim.Matched
			report.WikiClaim.Actual += result.WikiClaim.Actual
			report.WikiClaim.Expected += result.WikiClaim.Expected
		}
		if result.WikiClaimGrounding.Available {
			report.WikiClaimGrounding.Available = true
			report.WikiClaimGrounding.Passing += result.WikiClaimGrounding.Passing
			report.WikiClaimGrounding.Population += result.WikiClaimGrounding.Population
		}
	}
	finalizePrecisionRecall(&report.SummaryFact)
	finalizePrecisionRecall(&report.GraphRelation)
	finalizeAccuracy(&report.EntityResolution)
	finalizePrecisionRecall(&report.WikiClaim)
	finalizeRate(&report.WikiClaimGrounding)
	return report
}

func precisionRecall(matched, actual, expected int) PrecisionRecall {
	metric := PrecisionRecall{Available: expected > 0, Matched: matched, Actual: actual, Expected: expected}
	finalizePrecisionRecall(&metric)
	return metric
}

func finalizePrecisionRecall(metric *PrecisionRecall) {
	if metric == nil || !metric.Available || metric.Expected <= 0 {
		if metric != nil {
			metric.Precision = nil
			metric.Recall = nil
		}
		return
	}
	precision := 0.0
	if metric.Actual > 0 {
		precision = float64(metric.Matched) / float64(metric.Actual)
	}
	recall := float64(metric.Matched) / float64(metric.Expected)
	metric.Precision = floatPointer(precision)
	metric.Recall = floatPointer(recall)
}

func accuracy(matched, expected int) Accuracy {
	metric := Accuracy{Available: expected > 0, Matched: matched, Expected: expected}
	finalizeAccuracy(&metric)
	return metric
}

func finalizeAccuracy(metric *Accuracy) {
	if metric == nil || !metric.Available || metric.Expected <= 0 {
		if metric != nil {
			metric.Accuracy = nil
		}
		return
	}
	metric.Accuracy = floatPointer(float64(metric.Matched) / float64(metric.Expected))
}

func rate(passing, population int) Rate {
	metric := Rate{Available: population > 0, Passing: passing, Population: population}
	finalizeRate(&metric)
	return metric
}

func finalizeRate(metric *Rate) {
	if metric == nil || !metric.Available || metric.Population <= 0 {
		if metric != nil {
			metric.Rate = nil
		}
		return
	}
	metric.Rate = floatPointer(float64(metric.Passing) / float64(metric.Population))
}

func matchTexts(actual []string, expected []TextExpectation) int {
	used := make([]bool, len(expected))
	matched := 0
	for _, value := range actual {
		for i, item := range expected {
			if used[i] || !textExpectationMatches(value, item) {
				continue
			}
			used[i] = true
			matched++
			break
		}
	}
	return matched
}

func textExpectationMatches(actual string, expected TextExpectation) bool {
	actual = normalize(actual)
	if actual == "" {
		return false
	}
	if actual == normalize(expected.Text) {
		return true
	}
	for _, alias := range expected.Alternatives {
		if actual == normalize(alias) {
			return true
		}
	}
	return false
}

func matchRelations(actual []RelationActual, expected []RelationExpectation) int {
	used := make([]bool, len(expected))
	matched := 0
	for _, value := range actual {
		for i, item := range expected {
			if used[i] || !relationMatches(value, item) {
				continue
			}
			used[i] = true
			matched++
			break
		}
	}
	return matched
}

func relationMatches(actual RelationActual, expected RelationExpectation) bool {
	return matchesOne(actual.Source, expected.Source, expected.SourceAliases) &&
		matchesOne(actual.RelationType, expected.RelationType, expected.RelationTypeAliases) &&
		matchesOne(actual.Target, expected.Target, expected.TargetAliases)
}

func matchMappings(actual []EntityMappingActual, expected []EntityMappingExpectation) int {
	used := make([]bool, len(expected))
	matched := 0
	for _, value := range actual {
		for i, item := range expected {
			if used[i] || !mappingMatches(value, item) {
				continue
			}
			used[i] = true
			matched++
			break
		}
	}
	return matched
}

func mappingMatches(actual EntityMappingActual, expected EntityMappingExpectation) bool {
	return matchesOne(actual.SurfaceForm, expected.SurfaceForm, expected.SurfaceAliases) &&
		normalize(actual.EntityType) == normalize(expected.EntityType) &&
		matchesOne(actual.CanonicalName, expected.CanonicalName, expected.CanonicalAliases)
}

func matchesOne(actual, primary string, aliases []string) bool {
	actual = normalize(actual)
	if actual == "" {
		return false
	}
	if actual == normalize(primary) {
		return true
	}
	for _, alias := range aliases {
		if actual == normalize(alias) {
			return true
		}
	}
	return false
}

func uniqueTexts(values []string) []string {
	seen := make(map[string]struct{}, len(values))
	out := make([]string, 0, len(values))
	for _, value := range values {
		key := normalize(value)
		if key == "" {
			continue
		}
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, value)
	}
	return out
}

func uniqueRelations(values []RelationActual) []RelationActual {
	seen := make(map[string]struct{}, len(values))
	out := make([]RelationActual, 0, len(values))
	for _, value := range values {
		key := normalize(value.Source) + "\x1f" + normalize(value.RelationType) + "\x1f" + normalize(value.Target)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, value)
	}
	return out
}

func uniqueMappings(values []EntityMappingActual) []EntityMappingActual {
	seen := make(map[string]struct{}, len(values))
	out := make([]EntityMappingActual, 0, len(values))
	for _, value := range values {
		key := normalize(value.SurfaceForm) + "\x1f" + normalize(value.EntityType) + "\x1f" + normalize(value.CanonicalName)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, value)
	}
	return out
}

func normalize(value string) string {
	return strings.ToLower(strings.Join(strings.Fields(strings.TrimSpace(value)), " "))
}

func floatPointer(value float64) *float64 { return &value }
