package wiki

import (
	"fmt"
	"strings"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

func validateDecisions(facts map[string]enterprisesummary.Fact, input []FactDecision) (CoverageResult, map[string]FactDecision, error) {
	seen := make(map[string]FactDecision, len(input))
	coverage := CoverageResult{Pass: true}
	for _, decision := range input {
		decision.FactID = strings.TrimSpace(decision.FactID)
		if _, ok := facts[decision.FactID]; !ok {
			return CoverageResult{}, nil, fmt.Errorf("wiki decision references unknown fact %q", decision.FactID)
		}
		if _, duplicate := seen[decision.FactID]; duplicate {
			return CoverageResult{}, nil, fmt.Errorf("wiki decision duplicated for fact %s", decision.FactID)
		}
		if !decision.WikiEligible && strings.TrimSpace(decision.SkipReason) == "" {
			return CoverageResult{}, nil, fmt.Errorf("wiki skip reason required for fact %s", decision.FactID)
		}
		seen[decision.FactID] = decision
		if decision.WikiEligible {
			coverage.EligibleFacts++
		} else {
			coverage.SkippedFacts++
		}
	}
	for factID := range facts {
		if _, ok := seen[factID]; !ok {
			return CoverageResult{}, nil, fmt.Errorf("wiki decision missing for fact %s", factID)
		}
	}
	return coverage, seen, nil
}
