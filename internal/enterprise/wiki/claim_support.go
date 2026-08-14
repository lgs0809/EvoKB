package wiki

import (
	"fmt"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

func claimSupport(ids []string, facts map[string]enterprisesummary.Fact, decisions map[string]FactDecision) ([]enterprisesummary.Fact, []string, error) {
	var support []enterprisesummary.Fact
	var evidenceIDs []string
	for _, factID := range ids {
		fact, ok := facts[factID]
		if !ok || !decisions[factID].WikiEligible {
			return nil, nil, fmt.Errorf("wiki claim references ineligible fact %s", factID)
		}
		support = append(support, fact)
		evidenceIDs = append(evidenceIDs, fact.EvidenceIDs...)
	}
	return support, uniqueStrings(evidenceIDs), nil
}
