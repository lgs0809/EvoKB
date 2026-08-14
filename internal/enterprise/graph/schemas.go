package graph

import "github.com/xoai/sage-wiki/internal/llm"

var extractionSchema = llm.JSONSchema{
	Name:        "enterprise_graph_extraction",
	Description: "typed enterprise entities and evidence-linked relations",
	Schema: map[string]any{
		"type": "object",
		"properties": map[string]any{
			"entities": map[string]any{
				"type": "array",
				"items": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"name": map[string]any{"type": "string"},
						"type": map[string]any{"type": "string"},
					},
					"required": []string{"name", "type"},
				},
			},
			"relations": map[string]any{
				"type": "array",
				"items": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"source_name": map[string]any{"type": "string"},
						"source_type": map[string]any{"type": "string"},
						"relation_type": map[string]any{"type": "string"},
						"target_name": map[string]any{"type": "string"},
						"target_type": map[string]any{"type": "string"},
						"supporting_fact_ids": map[string]any{
							"type": "array",
							"items": map[string]any{"type": "string"},
						},
						"valid_from": map[string]any{"type": "string"},
						"valid_to": map[string]any{"type": "string"},
						"confidence": map[string]any{"type": "number"},
					},
					"required": []string{"source_name", "source_type", "relation_type", "target_name", "target_type", "supporting_fact_ids", "valid_from", "valid_to", "confidence"},
				},
			},
			"fact_decisions": map[string]any{
				"type": "array",
				"items": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"fact_id": map[string]any{"type": "string"},
						"graph_eligible": map[string]any{"type": "boolean"},
						"skip_reason": map[string]any{"type": "string"},
					},
					"required": []string{"fact_id", "graph_eligible", "skip_reason"},
				},
			},
		},
		"required": []string{"entities", "relations", "fact_decisions"},
	},
}
