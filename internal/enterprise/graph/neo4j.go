package graph

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

type Neo4jClient struct {
	BaseURL string
	User string
	Password string
	Database string
	Client *http.Client
}

type neo4jStatement struct {
	Statement string `json:"statement"`
	Parameters map[string]any `json:"parameters,omitempty"`
}

type neo4jRequest struct { Statements []neo4jStatement `json:"statements"` }
type neo4jError struct { Code string `json:"code"`; Message string `json:"message"` }
type neo4jResponse struct { Errors []neo4jError `json:"errors"` }

func NewNeo4jClient(baseURL, user, password, database string) *Neo4jClient {
	if strings.TrimSpace(database) == "" { database = "neo4j" }
	return &Neo4jClient{
		BaseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"),
		User: user, Password: password, Database: database,
		Client: &http.Client{Timeout: 30 * time.Second},
	}
}

func (n *Neo4jClient) EnsureSchema(ctx context.Context) error {
	return n.exec(ctx,
		neo4jStatement{Statement: "CREATE CONSTRAINT enterprise_entity_id IF NOT EXISTS FOR (n:Entity) REQUIRE n.id IS UNIQUE"},
		neo4jStatement{Statement: "CREATE CONSTRAINT enterprise_support_id IF NOT EXISTS FOR (n:EvidenceSupport) REQUIRE n.id IS UNIQUE"},
		neo4jStatement{Statement: "CREATE CONSTRAINT enterprise_evidence_id IF NOT EXISTS FOR (n:Evidence) REQUIRE n.id IS UNIQUE"},
		neo4jStatement{Statement: "CREATE INDEX enterprise_entity_type IF NOT EXISTS FOR (n:Entity) ON (n.entity_type)"},
		neo4jStatement{Statement: "CREATE FULLTEXT INDEX enterprise_entity_fulltext IF NOT EXISTS FOR (n:Entity) ON EACH [n.name, n.canonical_key]"},
	)
}

func (n *Neo4jClient) UpsertEntity(ctx context.Context, entity CanonicalEntity) error {
	if entity.ID == "" || entity.CanonicalName == "" || entity.EntityType == "" {
		return fmt.Errorf("neo4j entity: id, name and type are required")
	}
	return n.exec(ctx, neo4jStatement{
		Statement: "MERGE (n:Entity {id:$id}) SET n.name=$name,n.entity_type=$entity_type,n.canonical_key=$canonical_key,n.updated_at=datetime()",
		Parameters: map[string]any{"id": entity.ID, "name": entity.CanonicalName, "entity_type": entity.EntityType, "canonical_key": entity.CanonicalKey},
	})
}

func (n *Neo4jClient) UpsertRelation(ctx context.Context, relation RelationFact, supports []EvidenceSupport) error {
	if _, ok := relationRules[relation.RelationType]; !ok {
		return fmt.Errorf("neo4j relation %s: unsupported type %q", relation.ID, relation.RelationType)
	}
	if relation.ID == "" || relation.SourceEntityID == "" || relation.TargetEntityID == "" {
		return fmt.Errorf("neo4j relation: id and endpoints are required")
	}
	evidenceIDs := make([]string, 0, len(supports))
	supportIDs := make([]string, 0, len(supports))
	supportPayload := make([]map[string]any, 0, len(supports))
	seenEvidence := map[string]struct{}{}
	for _, support := range supports {
		supportIDs = append(supportIDs, support.ID)
		if _, ok := seenEvidence[support.EvidenceID]; !ok {
			seenEvidence[support.EvidenceID] = struct{}{}
			evidenceIDs = append(evidenceIDs, support.EvidenceID)
		}
		supportPayload = append(supportPayload, map[string]any{
			"id": support.ID, "relation_id": support.RelationID, "origin": support.Origin,
			"evidence_id": support.EvidenceID, "summary_id": support.SummaryID, "wiki_id": support.WikiID,
			"confidence": support.Confidence, "active": support.Active,
		})
	}
	sort.Strings(evidenceIDs)
	sort.Strings(supportIDs)
	businessQuery := fmt.Sprintf("MATCH (s:Entity {id:$source_id}),(t:Entity {id:$target_id}) MERGE (s)-[r:%s {relation_id:$relation_id}]->(t) SET r.valid_from=$valid_from,r.valid_to=$valid_to,r.conflict_status=$conflict_status,r.lifecycle=$lifecycle,r.updated_at=datetime(),r.evidence_ids=$evidence_ids,r.support_ids=$support_ids", relation.RelationType)
	statements := []neo4jStatement{{Statement: businessQuery, Parameters: map[string]any{
		"source_id": relation.SourceEntityID, "target_id": relation.TargetEntityID, "relation_id": relation.ID,
		"valid_from": timeParam(relation.ValidFrom), "valid_to": timeParam(relation.ValidTo),
		"conflict_status": relation.ConflictStatus, "lifecycle": relation.Lifecycle,
		"evidence_ids": evidenceIDs, "support_ids": supportIDs,
	}}}
	if len(supportPayload) > 0 {
		statements = append(statements, neo4jStatement{
			Statement: "MATCH (source:Entity {id:$source_id}),(target:Entity {id:$target_id}) UNWIND $supports AS item MERGE (support:EvidenceSupport {id:item.id}) SET support.relation_id=item.relation_id,support.origin=item.origin,support.evidence_id=item.evidence_id,support.summary_id=item.summary_id,support.wiki_id=item.wiki_id,support.confidence=item.confidence,support.active=item.active,support.updated_at=datetime() MERGE (evidence:Evidence {id:item.evidence_id}) MERGE (support)-[:USES_EVIDENCE]->(evidence) MERGE (support)-[:SUPPORTS_SOURCE]->(source) MERGE (support)-[:SUPPORTS_TARGET]->(target)",
			Parameters: map[string]any{"source_id": relation.SourceEntityID, "target_id": relation.TargetEntityID, "supports": supportPayload},
		})
	}
	return n.exec(ctx, statements...)
}

func (n *Neo4jClient) exec(ctx context.Context, statements ...neo4jStatement) error {
	if n == nil || strings.TrimSpace(n.BaseURL) == "" {
		return fmt.Errorf("neo4j base URL is required")
	}
	if len(statements) == 0 {
		return nil
	}
	body, err := json.Marshal(neo4jRequest{Statements: statements})
	if err != nil {
		return err
	}
	endpoint := n.BaseURL + "/db/" + url.PathEscape(n.Database) + "/tx/commit"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
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
		return fmt.Errorf("neo4j request: %w", err)
	}
	defer resp.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return fmt.Errorf("neo4j response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("neo4j HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(payload)))
	}
	var decoded neo4jResponse
	if err := json.Unmarshal(payload, &decoded); err != nil {
		return fmt.Errorf("neo4j decode: %w", err)
	}
	if len(decoded.Errors) > 0 {
		return fmt.Errorf("neo4j %s: %s", decoded.Errors[0].Code, decoded.Errors[0].Message)
	}
	return nil
}

func timeParam(value *time.Time) any {
	if value == nil {
		return nil
	}
	return value.UTC().Format(time.RFC3339Nano)
}
