package wiki

import "github.com/xoai/sage-wiki/internal/llm"

var proposalSchema = llm.JSONSchema{
	Name: "enterprise_wiki_proposal",
	Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"wiki_type": map[string]any{"type": "string", "enum": []any{TypeCompany, TypePerson, TypeContract, TypeLegalCase, TypeProject, TypeControlTopic, TypeEquityTopic, TypeGenericEntity, TypeGenericTopic}},
			"title":     map[string]any{"type": "string"},
			"claims": map[string]any{"type": "array", "items": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"section":             map[string]any{"type": "string"},
					"claim_text":          map[string]any{"type": "string"},
					"supporting_fact_ids": map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
				},
				"required": []string{"section", "claim_text", "supporting_fact_ids"},
			}},
			"fact_decisions": map[string]any{"type": "array", "items": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"fact_id":       map[string]any{"type": "string"},
					"wiki_eligible": map[string]any{"type": "boolean"},
					"skip_reason":   map[string]any{"type": "string"},
				},
				"required": []string{"fact_id", "wiki_eligible", "skip_reason"},
			}},
		},
		"required": []string{"wiki_type", "title", "claims", "fact_decisions"},
	},
}

var claimSupportSchema = llm.JSONSchema{
	Name: "enterprise_wiki_claim_support",
	Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"supported": map[string]any{"type": "boolean"},
			"reason":    map[string]any{"type": "string"},
		},
		"required": []string{"supported", "reason"},
	},
}
