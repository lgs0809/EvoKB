package graph

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNeo4jUpsertRelationProjectsWikiVersion(t *testing.T) {
	var captured neo4jRequest
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer r.Body.Close()
		if err := json.NewDecoder(r.Body).Decode(&captured); err != nil {
			t.Fatalf("decode request: %v", err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"errors":[]}`))
	}))
	defer server.Close()

	client := NewNeo4jClient(server.URL, "", "", "neo4j")
	version := uint64(7)
	relation := RelationFact{
		ID: "relation-1", SourceEntityID: "source-1", RelationType: RelationRelatedTo,
		TargetEntityID: "target-1", ConflictStatus: "NONE", Lifecycle: "ACTIVE",
	}
	support := EvidenceSupport{
		ID: "support-1", RelationID: relation.ID, Origin: OriginWikiKeyword,
		EvidenceID: "evidence-1", WikiID: "wiki-1", WikiVersion: &version,
		Confidence: 0.85, Active: true,
	}
	if err := client.UpsertRelation(t.Context(), relation, []EvidenceSupport{support}); err != nil {
		t.Fatal(err)
	}
	if len(captured.Statements) != 2 {
		t.Fatalf("statements=%d want=2", len(captured.Statements))
	}
	statement := captured.Statements[1]
	if !strings.Contains(statement.Statement, "support.wiki_version=item.wiki_version") {
		t.Fatalf("wiki_version is not projected in cypher: %s", statement.Statement)
	}
	supports, ok := statement.Parameters["supports"].([]any)
	if !ok || len(supports) != 1 {
		t.Fatalf("supports payload=%#v", statement.Parameters["supports"])
	}
	item, ok := supports[0].(map[string]any)
	if !ok {
		t.Fatalf("support item=%#v", supports[0])
	}
	if got, ok := item["wiki_version"].(float64); !ok || got != 7 {
		t.Fatalf("wiki_version=%#v want=7", item["wiki_version"])
	}
}
