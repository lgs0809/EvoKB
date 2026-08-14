package summary

import "github.com/xoai/sage-wiki/internal/llm"

var supportSchema = llm.JSONSchema{
	Name: "enterprise_fact_support",
	Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"supported": map[string]any{"type": "boolean"},
			"reason":    map[string]any{"type": "string"},
		},
		"required": []string{"supported", "reason"},
	},
}

var coverageSchema = llm.JSONSchema{
	Name: "enterprise_summary_coverage",
	Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"missing": map[string]any{
				"type": "array",
				"items": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"missing_fact_candidate": map[string]any{"type": "string"},
						"section":                map[string]any{"type": "string"},
						"source_block_ids":       map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "minItems": 1},
					},
					"required": []string{"missing_fact_candidate", "section", "source_block_ids"},
				},
			},
		},
		"required": []string{"missing"},
	},
}
