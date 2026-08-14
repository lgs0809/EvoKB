package summary

import "github.com/xoai/sage-wiki/internal/llm"

var classificationSchema = llm.JSONSchema{
	Name: "enterprise_document_classification",
	Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"document_type":  map[string]any{"type": "string"},
			"structure_type": map[string]any{"type": "string"},
			"units": map[string]any{
				"type": "array",
				"items": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"title":          map[string]any{"type": "string"},
						"document_type":  map[string]any{"type": "string"},
						"start_block_no": map[string]any{"type": "integer"},
						"end_block_no":   map[string]any{"type": "integer"},
					},
					"required": []string{"title", "document_type", "start_block_no", "end_block_no"},
				},
			},
		},
		"required": []string{"document_type", "structure_type"},
	},
}

var factsSchema = llm.JSONSchema{
	Name: "enterprise_summary_facts",
	Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"facts": map[string]any{
				"type": "array",
				"items": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"section":            map[string]any{"type": "string"},
						"fact_text":          map[string]any{"type": "string"},
						"evidence_block_ids": map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "minItems": 1},
					},
					"required": []string{"section", "fact_text", "evidence_block_ids"},
				},
			},
		},
		"required": []string{"facts"},
	},
}
