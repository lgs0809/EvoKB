package wiki

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/xoai/sage-wiki/internal/llm"
	"github.com/xoai/sage-wiki/internal/prompts"
)

type StructuredWriter struct {
	Client *llm.Client
	Model string
	IdentityName string
	MaxTokens int
}

func (w *StructuredWriter) Identity() string {
	if w == nil { return "" }
	if strings.TrimSpace(w.IdentityName) != "" { return strings.TrimSpace(w.IdentityName) }
	return strings.TrimSpace(w.Model)
}

func (w *StructuredWriter) Generate(ctx context.Context, template Template, source SourceSet) (Proposal, error) {
	if w == nil || w.Client == nil { return Proposal{}, fmt.Errorf("wiki writer client is required") }
	var facts strings.Builder
	for _, summary := range source.Summaries {
		for _, fact := range summary.Facts {
			if fact.GroundingStatus != "PASS" { continue }
			fmt.Fprintf(&facts, "<fact id=%q summary=%q>%s</fact>\n", fact.ID, summary.ID, prompts.NeutralizeTags(fact.FactText))
		}
	}
	var sections []string
	for _, section := range template.Sections { sections = append(sections, section.Name) }
	instruction := "Build a claim-first enterprise wiki from grounded facts only. Every claim must cite exact supporting fact IDs. Emit one eligibility decision for every input fact. Cross-source claims must not introduce a conclusion that is not directly supported by the cited facts. Use only these sections: " + strings.Join(sections, ", ") + ".\nFacts:\n" + facts.String()
	payload, _, err := w.Client.StructuredCompletion(ctx, []llm.Message{{Role: "system", Content: "Write structured enterprise wiki claims from grounded facts only."}, {Role: "user", Content: instruction}}, proposalSchema, llm.CallOpts{Model: w.Model, MaxTokens: w.tokenLimit(), Temperature: zeroTemperature()})
	if err != nil { return Proposal{}, err }
	var out Proposal
	if err := json.Unmarshal(payload, &out); err != nil { return Proposal{}, fmt.Errorf("wiki writer decode: %w", err) }
	return out, nil
}

func (w *StructuredWriter) tokenLimit() int {
	if w.MaxTokens > 0 { return w.MaxTokens }
	return 8192
}

func zeroTemperature() *float64 { value := 0.0; return &value }
