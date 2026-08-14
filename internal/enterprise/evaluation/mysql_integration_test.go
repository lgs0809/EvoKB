package evaluation

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestMySQLStoreLoadsPersistedArtifactsForExactVersion(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv("EKB_MYSQL_INTEGRATION_DSN"))
	if dsn == "" {
		t.Skip("EKB_MYSQL_INTEGRATION_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	store, err := OpenMySQL(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	docID := uuid.NewString()
	versionID := uuid.NewString()
	blockID := "rb_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	evidenceID := uuid.NewString()
	unitID := uuid.NewString()
	summaryID := uuid.NewString()
	factID := uuid.NewString()
	sourceCanonicalID := uuid.NewString()
	targetCanonicalID := uuid.NewString()
	sourceEntityID := uuid.NewString()
	relationID := uuid.NewString()
	supportID := uuid.NewString()
	wikiID := uuid.NewString()
	claimID := uuid.NewString()

	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := store.db.ExecContext(ctx, query, args...); err != nil {
			t.Fatalf("fixture exec: %v\nquery=%s", err, query)
		}
	}
	cleanup := func() {
		queries := []struct {
			query string
			args  []any
		}{
			{`DELETE FROM relation_evidence_support WHERE support_id=?`, []any{supportID}},
			{`DELETE FROM relation_fact WHERE relation_id=?`, []any{relationID}},
			{`DELETE FROM entity_mapping WHERE source_entity_id=?`, []any{sourceEntityID}},
			{`DELETE FROM source_entity WHERE source_entity_id=?`, []any{sourceEntityID}},
			{`DELETE FROM wiki_evidence WHERE claim_id=?`, []any{claimID}},
			{`DELETE FROM wiki_claim WHERE claim_id=?`, []any{claimID}},
			{`DELETE FROM wiki WHERE wiki_id=?`, []any{wikiID}},
			{`DELETE FROM summary_fact WHERE fact_id=?`, []any{factID}},
			{`DELETE FROM summary WHERE summary_id=?`, []any{summaryID}},
			{`DELETE FROM document_unit WHERE unit_id=?`, []any{unitID}},
			{`DELETE FROM canonical_entity WHERE canonical_entity_id IN (?,?)`, []any{sourceCanonicalID, targetCanonicalID}},
			{`DELETE FROM evidence WHERE evidence_id=?`, []any{evidenceID}},
			{`DELETE FROM raw_block WHERE block_id=?`, []any{blockID}},
			{`DELETE FROM document_version WHERE version_id=?`, []any{versionID}},
			{`DELETE FROM document WHERE document_id=?`, []any{docID}},
		}
		for _, item := range queries {
			_, _ = store.db.ExecContext(context.Background(), item.query, item.args...)
		}
	}
	defer cleanup()

	exec(`INSERT INTO document(document_id,logical_name,status) VALUES(?,?,'ACTIVE')`, docID, "artifact-eval-"+docID)
	exec(`INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key,activated_at) VALUES(?,?,1,'ACTIVE','eval.pdf',10,?,?,CURRENT_TIMESTAMP(6))`, versionID, docID, strings.Repeat("a", 64), "original/"+docID+"/"+versionID+"/eval.pdf")
	exec(`INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES(?,?,0,'text','Acme Holdings controls Acme Tech.',?)`, blockID, versionID, strings.Repeat("b", 64))
	exec(`INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash,active) VALUES(?,?,?,?,?,'{}',?,TRUE)`, evidenceID, docID, versionID, blockID, "original/"+docID+"/"+versionID+"/eval.pdf", strings.Repeat("b", 64))
	exec(`INSERT INTO document_unit(unit_id,version_id,unit_no,title,structure_type,document_type,boundary_json) VALUES(?,?,1,'Eval unit','single','General / Mixed','{"start_block_no":0,"end_block_no":0}')`, unitID, versionID)
	exec(`INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model) VALUES(?,?,?,?,?,'PASS','PASS','ACTIVE','integration')`, summaryID, unitID, versionID, "summary/"+summaryID+".md", strings.Repeat("c", 64))
	exec(`INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status) VALUES(?,?,'Ownership','Acme Holdings controls Acme Tech.','PASS')`, factID, summaryID)
	exec(`INSERT INTO canonical_entity(canonical_entity_id,canonical_name,entity_type,canonical_key) VALUES(?, 'Acme Holdings','COMPANY',?), (?, 'Acme Tech','COMPANY',?)`, sourceCanonicalID, "acme-holdings-"+sourceCanonicalID, targetCanonicalID, "acme-tech-"+targetCanonicalID)
	exec(`INSERT INTO source_entity(source_entity_id,version_id,surface_form,entity_type,evidence_id) VALUES(?,?,'ACME','COMPANY',?)`, sourceEntityID, versionID, evidenceID)
	exec(`INSERT INTO entity_mapping(source_entity_id,canonical_entity_id,confidence,status,decided_by) VALUES(?,?,0.99,'APPLIED','integration')`, sourceEntityID, sourceCanonicalID)
	exec(`INSERT INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,conflict_status,lifecycle) VALUES(?,?,'CONTROLS',?,'NONE','ACTIVE')`, relationID, sourceCanonicalID, targetCanonicalID)
	exec(`INSERT INTO relation_evidence_support(support_id,relation_id,origin,evidence_id,summary_id,confidence,active) VALUES(?,?,'llm_triple',?,?,0.99,TRUE)`, supportID, relationID, evidenceID, summaryID)
	exec(`INSERT INTO wiki(wiki_id,wiki_version,wiki_type,title,object_key,content_hash,lifecycle) VALUES(?,1,'COMPANY','Acme Holdings','wiki/acme.md',?,'ACTIVE')`, wikiID, strings.Repeat("d", 64))
	exec(`INSERT INTO wiki_claim(claim_id,wiki_id,wiki_version,section,claim_text,grounding_status,temporal_status) VALUES(?,?,1,'Ownership','Acme Tech is controlled by Acme Holdings.','PASS','PASS')`, claimID, wikiID)
	exec(`INSERT INTO wiki_evidence(claim_id,evidence_id) VALUES(?,?)`, claimID, evidenceID)

	actual, err := store.Load(ctx, []string{versionID})
	if err != nil {
		t.Fatal(err)
	}
	result := EvaluateCase(ArtifactGoldenCase{
		ID:                     "mysql-integration",
		VersionIDs:             []string{versionID},
		ExpectedSummaryFacts:   []TextExpectation{{Text: "Acme Holdings controls Acme Tech."}},
		ExpectedRelations:      []RelationExpectation{{Source: "Acme Holdings", RelationType: "CONTROLS", Target: "Acme Tech"}},
		ExpectedEntityMappings: []EntityMappingExpectation{{SurfaceForm: "ACME", EntityType: "COMPANY", CanonicalName: "Acme Holdings"}},
		ExpectedWikiClaims:     []TextExpectation{{Text: "Acme Tech is controlled by Acme Holdings."}},
	}, actual)
	assertFloat(t, result.SummaryFact.Precision, 1)
	assertFloat(t, result.SummaryFact.Recall, 1)
	assertFloat(t, result.GraphRelation.Precision, 1)
	assertFloat(t, result.GraphRelation.Recall, 1)
	assertFloat(t, result.EntityResolution.Accuracy, 1)
	assertFloat(t, result.WikiClaim.Precision, 1)
	assertFloat(t, result.WikiClaim.Recall, 1)
	assertFloat(t, result.WikiClaimGrounding.Rate, 1)
}
