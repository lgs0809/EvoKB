package wiki

import (
	"fmt"
	"strings"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

func sourceFacts(summaries []enterprisesummary.StoredSummary) (map[string]enterprisesummary.Fact, error) {
	facts := make(map[string]enterprisesummary.Fact)
	for _, source := range summaries {
		if source.ID == "" || source.Lifecycle != "ACTIVE" || source.GroundingStatus != "PASS" || source.CoverageStatus != "PASS" {
			return nil, fmt.Errorf("wiki source summary %s is not eligible", source.ID)
		}
		for _, fact := range source.Facts {
			if fact.GroundingStatus != "PASS" || strings.TrimSpace(fact.ID) == "" {
				continue
			}
			if len(fact.EvidenceIDs) == 0 {
				return nil, fmt.Errorf("wiki source fact %s has no raw evidence", fact.ID)
			}
			if _, exists := facts[fact.ID]; exists {
				return nil, fmt.Errorf("duplicate wiki fact %s", fact.ID)
			}
			facts[fact.ID] = fact
		}
	}
	if len(facts) == 0 {
		return nil, fmt.Errorf("wiki has no grounded source facts")
	}
	return facts, nil
}
