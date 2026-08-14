package graph

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// SearchHit is a bounded, evidence-backed graph retrieval result.
type SearchHit struct {
	RelationID     string   `json:"relation_id"`
	SourceEntityID string   `json:"source_entity_id"`
	SourceName     string   `json:"source_name"`
	TargetEntityID string   `json:"target_entity_id"`
	TargetName     string   `json:"target_name"`
	RelationType   string   `json:"relation_type"`
	EvidenceIDs    []string `json:"evidence_ids"`
	Score          float64  `json:"score"`
}

// SearchBounded seeds traversal from the enterprise entity full-text index and
// expands at most two graph hops. Only relations with at least one ACTIVE
// EvidenceSupport are returned, so graph retrieval can always resolve back to
// Raw Evidence before a result is exposed to callers.
func (n *Neo4jClient) SearchBounded(ctx context.Context, query string, maxDepth, maxExpand int) ([]SearchHit, error) {
	if n == nil || strings.TrimSpace(n.BaseURL) == "" {
		return nil, fmt.Errorf("neo4j base URL is required")
	}
	query = strings.TrimSpace(query)
	if query == "" {
		return nil, nil
	}
	if maxDepth <= 0 || maxDepth > 2 {
		maxDepth = 2
	}
	if maxExpand <= 0 || maxExpand > 10 {
		maxExpand = 10
	}

	statement := `CALL db.index.fulltext.queryNodes('enterprise_entity_fulltext', $query, {limit: $max_expand}) YIELD node, score
MATCH p=(node)-[*1..2]-(other:Entity)
WHERE length(p) <= $max_depth
UNWIND relationships(p) AS r
WITH score, startNode(r) AS source, endNode(r) AS target, r
WHERE coalesce(r.lifecycle, 'ACTIVE') = 'ACTIVE' AND r.relation_id IS NOT NULL
OPTIONAL MATCH (support:EvidenceSupport {relation_id:r.relation_id})-[:USES_EVIDENCE]->(evidence:Evidence)
WHERE support.active = true
WITH score, source, target, r, collect(DISTINCT evidence.id) AS evidence_ids
WHERE size(evidence_ids) > 0
RETURN DISTINCT r.relation_id, source.id, source.name, target.id, target.name, type(r), evidence_ids, score
ORDER BY score DESC
LIMIT $max_expand`

	body, err := json.Marshal(neo4jRequest{Statements: []neo4jStatement{{
		Statement: statement,
		Parameters: map[string]any{"query": query, "max_depth": maxDepth, "max_expand": maxExpand},
	}}})
	if err != nil {
		return nil, err
	}
	endpoint := strings.TrimRight(n.BaseURL, "/") + "/db/" + url.PathEscape(n.Database) + "/tx/commit"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if n.User != "" {
		req.SetBasicAuth(n.User, n.Password)
	}
	client := n.Client
	if client == nil {
		client = &http.Client{Timeout: 30 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("neo4j search request: %w", err)
	}
	defer resp.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, fmt.Errorf("neo4j search response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("neo4j search HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(payload)))
	}

	var decoded struct {
		Results []struct {
			Data []struct {
				Row []any `json:"row"`
			} `json:"data"`
		} `json:"results"`
		Errors []neo4jError `json:"errors"`
	}
	if err := json.Unmarshal(payload, &decoded); err != nil {
		return nil, fmt.Errorf("neo4j search decode: %w", err)
	}
	if len(decoded.Errors) > 0 {
		return nil, fmt.Errorf("neo4j %s: %s", decoded.Errors[0].Code, decoded.Errors[0].Message)
	}
	if len(decoded.Results) == 0 {
		return nil, nil
	}

	out := make([]SearchHit, 0, len(decoded.Results[0].Data))
	for _, item := range decoded.Results[0].Data {
		row := item.Row
		if len(row) != 8 {
			return nil, fmt.Errorf("neo4j search: unexpected row width %d", len(row))
		}
		evidenceIDs, err := stringSlice(row[6])
		if err != nil {
			return nil, fmt.Errorf("neo4j search evidence ids: %w", err)
		}
		out = append(out, SearchHit{
			RelationID: stringValue(row[0]), SourceEntityID: stringValue(row[1]), SourceName: stringValue(row[2]),
			TargetEntityID: stringValue(row[3]), TargetName: stringValue(row[4]), RelationType: stringValue(row[5]),
			EvidenceIDs: evidenceIDs, Score: floatValue(row[7]),
		})
	}
	return out, nil
}

func stringValue(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func floatValue(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case float32:
		return float64(x)
	case int:
		return float64(x)
	case int64:
		return float64(x)
	default:
		return 0
	}
}

func stringSlice(v any) ([]string, error) {
	items, ok := v.([]any)
	if !ok {
		if stringsList, ok := v.([]string); ok {
			return stringsList, nil
		}
		return nil, fmt.Errorf("unexpected value %T", v)
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		s, ok := item.(string)
		if !ok {
			return nil, fmt.Errorf("unexpected item %T", item)
		}
		out = append(out, s)
	}
	return out, nil
}
