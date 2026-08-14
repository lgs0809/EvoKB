package graph

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"
)

func TestNeo4jProjectionIntegration(t *testing.T) {
	baseURL := strings.TrimSpace(os.Getenv("EKB_NEO4J_URL"))
	auth := strings.TrimSpace(os.Getenv("EKB_NEO4J_AUTH"))
	if baseURL == "" || auth == "" {
		t.Skip("EKB_NEO4J_URL and EKB_NEO4J_AUTH are required for integration test")
	}
	parts := strings.SplitN(auth, "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		t.Fatal("EKB_NEO4J_AUTH must be user/password")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	client := NewNeo4jClient(baseURL, parts[0], parts[1], "neo4j")
	if err := client.EnsureSchema(ctx); err != nil {
		t.Fatalf("ensure schema: %v", err)
	}

	source := CanonicalEntity{ID: "it-entity-source", CanonicalName: "Integration Source", EntityType: EntityCompany, CanonicalKey: "integration source"}
	target := CanonicalEntity{ID: "it-entity-target", CanonicalName: "Integration Target", EntityType: EntityCompany, CanonicalKey: "integration target"}
	if err := client.UpsertEntity(ctx, source); err != nil {
		t.Fatalf("upsert source: %v", err)
	}
	if err := client.UpsertEntity(ctx, target); err != nil {
		t.Fatalf("upsert target: %v", err)
	}

	relation := RelationFact{
		ID:             "it-relation-related",
		SourceEntityID: source.ID,
		RelationType:   RelationRelatedTo,
		TargetEntityID: target.ID,
		ConflictStatus: "NONE",
		Lifecycle:      "ACTIVE",
	}
	supports := []EvidenceSupport{{
		ID:         "it-support-related",
		RelationID: relation.ID,
		Origin:     OriginLLMTriple,
		EvidenceID: "it-evidence-related",
		SummaryID:  "it-summary-related",
		Confidence: 0.99,
		Active:     true,
	}}
	if err := client.UpsertRelation(ctx, relation, supports); err != nil {
		t.Fatalf("upsert relation: %v", err)
	}

	hits, err := client.SearchBounded(ctx, "Integration Source", 2, 10)
	if err != nil {
		t.Fatalf("bounded search: %v", err)
	}
	if len(hits) == 0 {
		t.Fatal("bounded search returned no evidence-backed relation")
	}
	found := false
	for _, hit := range hits {
		if hit.RelationID == relation.ID && len(hit.EvidenceIDs) == 1 && hit.EvidenceIDs[0] == supports[0].EvidenceID {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("bounded search did not return relation %s with evidence %s: %#v", relation.ID, supports[0].EvidenceID, hits)
	}
}
