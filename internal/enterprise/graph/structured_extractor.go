package graph

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
	"github.com/xoai/sage-wiki/internal/llm"
	"github.com/xoai/sage-wiki/internal/prompts"
)

type StructuredExtractor struct {
	Client       *llm.Client
	Model        string
	IdentityName string
	MaxTokens    int
}

func (e *StructuredExtractor) Identity() string {
	if e == nil {
		return ""
	}
	if strings.TrimSpace(e.IdentityName) != "" {
		return strings.TrimSpace(e.IdentityName)
	}
	return strings.TrimSpace(e.Model)
}

func (e *StructuredExtractor) Extract(ctx context.Context, source enterprisesummary.StoredSummary) (Extraction, error) {
	if e == nil || e.Client == nil {
		return Extraction{}, fmt.Errorf("graph extractor: llm client is required")
	}
	if source.GroundingStatus != "PASS" || source.CoverageStatus != "PASS" || source.Lifecycle != "ACTIVE" {
		return Extraction{}, fmt.Errorf("graph extractor: summary %s is not eligible", source.ID)
	}
	entityTypes := AllowedEntityTypes()
	relationTypes := AllowedRelationTypes()
	sort.Strings(entityTypes)
	sort.Strings(relationTypes)
	var facts strings.Builder
	for _, fact := range source.Facts {
		if fact.GroundingStatus == "PASS" {
			fmt.Fprintf(&facts, "<fact id=%q section=%q>\n%s\n</fact>\n", fact.ID, fact.Section, prompts.NeutralizeTags(fact.FactText))
		}
	}
	if facts.Len() == 0 {
		return Extraction{}, fmt.Errorf("graph extractor: summary %s has no grounded facts", source.ID)
	}
	instruction := "Extract typed enterprise graph relations from grounded facts. Every relation must cite exact supporting fact IDs. " +
		"Use only the allowed types. Emit one fact_decision for every input fact. Eligible facts must be cited by at least one relation; non-eligible facts need a skip_reason. " +
		"Business valid_from/valid_to must remain empty unless explicitly stated.\nEntity types:\n- " + strings.Join(entityTypes, "\n- ") +
		"\nRelation types:\n- " + strings.Join(relationTypes, "\n- ") + "\nFacts:\n" + facts.String()
	payload, _, err := e.Client.StructuredCompletion(ctx, []llm.Message{
		{Role: "system", Content: "Extract a strict enterprise knowledge graph from grounded summary facts. Return structured data only."},
		{Role: "user", Content: instruction},
	}, extractionSchema, llm.CallOpts{Model: e.Model, MaxTokens: e.tokenLimit(), Temperature: zeroTemperature()})
	if err != nil {
		return Extraction{}, err
	}
	var out Extraction
	if err := json.Unmarshal(payload, &out); err != nil {
		return Extraction{}, fmt.Errorf("graph extractor decode: %w", err)
	}
	return out, nil
}

func (e *StructuredExtractor) tokenLimit() int {
	if e.MaxTokens > 0 {
		return e.MaxTokens
	}
	return 8192
}

func zeroTemperature() *float64 {
	v := 0.0
	return &v
}
