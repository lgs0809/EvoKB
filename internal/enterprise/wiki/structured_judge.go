package wiki

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
	"github.com/xoai/sage-wiki/internal/llm"
	"github.com/xoai/sage-wiki/internal/prompts"
)

type StructuredJudge struct {
	Client       *llm.Client
	Model        string
	IdentityName string
	MaxTokens    int
}

func (j *StructuredJudge) Identity() string {
	if j == nil { return "" }
	if strings.TrimSpace(j.IdentityName) != "" { return strings.TrimSpace(j.IdentityName) }
	return strings.TrimSpace(j.Model)
}

func (j *StructuredJudge) SupportsClaim(ctx context.Context, claim ProposedClaim, facts []enterprisesummary.Fact) (bool, string, error) {
	if j == nil || j.Client == nil { return false, "", fmt.Errorf("wiki judge client is required") }
	var source strings.Builder
	for _, fact := range facts {
		fmt.Fprintf(&source, "<fact id=%q summary=%q>%s</fact>\n", fact.ID, fact.SummaryID, prompts.NeutralizeTags(fact.FactText))
	}
	instruction := "Decide whether the claim is fully supported by the cited grounded facts. A cross-source claim passes only if the exact factual conclusion follows directly from the cited facts; do not allow synthesis that adds an unstated causal, ownership, control, temporal, or legal conclusion.\nClaim:\n" + prompts.NeutralizeTags(claim.ClaimText) + "\nFacts:\n" + source.String()
	payload, _, err := j.Client.StructuredCompletion(ctx, []llm.Message{{Role: "system", Content: "Judge claim grounding conservatively. Return structured data only."}, {Role: "user", Content: instruction}}, claimSupportSchema, llm.CallOpts{Model: j.Model, MaxTokens: j.tokenLimit(), Temperature: zeroTemperature()})
	if err != nil { return false, "", err }
	var out struct { Supported bool `json:"supported"`; Reason string `json:"reason"` }
	if err := json.Unmarshal(payload, &out); err != nil { return false, "", fmt.Errorf("wiki judge decode: %w", err) }
	return out.Supported, strings.TrimSpace(out.Reason), nil
}

func (j *StructuredJudge) tokenLimit() int {
	if j.MaxTokens > 0 { return j.MaxTokens }
	return 2048
}
