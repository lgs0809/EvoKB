package raw

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"
)

func TestActivateVersionInvalidatesDerivedKnowledgeWithoutChangingBusinessValidity(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv("EKB_MYSQL_INTEGRATION_DSN"))
	if dsn == "" {
		t.Skip("EKB_MYSQL_INTEGRATION_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	store, err := OpenMySQL(ctx, dsn)
	if err != nil {
		t.Fatalf("open mysql: %v", err)
	}
	defer store.Close()

	docID := newID()
	oldVersion := newID()
	newVersion := newID()
	oldUnit := newID()
	oldSummary := newID()
	wikiID := newID()
	claimID := newID()
	oldBlock := "rb_" + strings.ReplaceAll(newID(), "-", "")
	newBlock := "rb_" + strings.ReplaceAll(newID(), "-", "")
	oldEvidence := newID()
	newEvidence := newID()
	sourceEntity := newID()
	targetEntity := newID()
	relationID := newID()
	supportID := newID()

	cleanup := func() {
		statements := []struct {
			query string
			args  []any
		}{
			{`DELETE FROM relation_evidence_support WHERE support_id=?`, []any{supportID}},
			{`DELETE FROM relation_fact WHERE relation_id=?`, []any{relationID}},
			{`DELETE FROM canonical_entity WHERE canonical_entity_id IN (?,?)`, []any{sourceEntity, targetEntity}},
			{`DELETE FROM wiki_evidence WHERE claim_id=?`, []any{claimID}},
			{`DELETE FROM wiki_claim_fact WHERE claim_id=?`, []any{claimID}},
			{`DELETE FROM wiki_claim WHERE claim_id=?`, []any{claimID}},
			{`DELETE FROM wiki WHERE wiki_id=?`, []any{wikiID}},
			{`DELETE FROM summary WHERE summary_id=?`, []any{oldSummary}},
			{`DELETE FROM document_unit WHERE unit_id=?`, []any{oldUnit}},
			{`DELETE FROM evidence WHERE evidence_id IN (?,?)`, []any{oldEvidence, newEvidence}},
			{`DELETE FROM raw_block WHERE block_id IN (?,?)`, []any{oldBlock, newBlock}},
			{`DELETE FROM document_version WHERE version_id IN (?,?)`, []any{oldVersion, newVersion}},
			{`DELETE FROM document WHERE document_id=?`, []any{docID}},
		}
		for _, statement := range statements {
			_, _ = store.db.ExecContext(context.Background(), statement.query, statement.args...)
		}
	}
	defer cleanup()

	mustExec := func(query string, args ...any) {
		t.Helper()
		if _, err := store.db.ExecContext(ctx, query, args...); err != nil {
			t.Fatalf("fixture exec failed: %v\nquery=%s", err, query)
		}
	}

	mustExec(`INSERT INTO document(document_id,logical_name,status) VALUES(?,?,'ACTIVE')`, docID, "lifecycle-integration-"+docID)
	mustExec(`INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key,activated_at) VALUES(?,?,1,'ACTIVE','old.pdf',10,?, ?,CURRENT_TIMESTAMP(6))`, oldVersion, docID, strings.Repeat("a", 64), "raw/"+oldVersion+"/old.pdf")
	mustExec(`INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES(?,?,2,'PENDING','new.pdf',11,?,?)`, newVersion, docID, strings.Repeat("b", 64), "raw/"+newVersion+"/new.pdf")
	mustExec(`INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES(?, ?,1,'text','old source',?)`, oldBlock, oldVersion, strings.Repeat("c", 64))
	mustExec(`INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,content_hash) VALUES(?, ?,1,'text','new source',?)`, newBlock, newVersion, strings.Repeat("d", 64))
	mustExec(`INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash,active) VALUES(?,?,?,?,?,'{}',?,TRUE)`, oldEvidence, docID, oldVersion, oldBlock, "raw/"+oldVersion+"/old.pdf", strings.Repeat("c", 64))
	mustExec(`INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash,active) VALUES(?,?,?,?,?,'{}',?,FALSE)`, newEvidence, docID, newVersion, newBlock, "raw/"+newVersion+"/new.pdf", strings.Repeat("d", 64))
	mustExec(`INSERT INTO document_unit(unit_id,version_id,unit_no,title,structure_type,document_type) VALUES(?,?,1,'old unit','single','General / Mixed')`, oldUnit, oldVersion)
	mustExec(`INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model) VALUES(?,?,?,?,?,'PASS','PASS','ACTIVE','integration')`, oldSummary, oldUnit, oldVersion, "summary/old.md", strings.Repeat("e", 64))
	mustExec(`INSERT INTO wiki(wiki_id,wiki_version,wiki_type,title,object_key,content_hash,lifecycle) VALUES(?,1,'GENERIC_TOPIC','old wiki','wiki/old.md',?,'ACTIVE')`, wikiID, strings.Repeat("f", 64))
	mustExec(`INSERT INTO wiki_claim(claim_id,wiki_id,wiki_version,section,claim_text,grounding_status,temporal_status) VALUES(?,?,1,'Overview','old claim','PASS','PASS')`, claimID, wikiID)
	mustExec(`INSERT INTO wiki_evidence(claim_id,evidence_id) VALUES(?,?)`, claimID, oldEvidence)
	mustExec(`INSERT INTO canonical_entity(canonical_entity_id,canonical_name,entity_type,canonical_key) VALUES(?, 'Source Co','COMPANY',?), (?, 'Target Co','COMPANY',?)`, sourceEntity, "source-"+sourceEntity, targetEntity, "target-"+targetEntity)
	mustExec(`INSERT INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,valid_from,valid_to,conflict_status,lifecycle) VALUES(?,?, 'CONTROLS',?,'2024-01-01 00:00:00','2030-01-01 00:00:00','NONE','ACTIVE')`, relationID, sourceEntity, targetEntity)
	mustExec(`INSERT INTO relation_evidence_support(support_id,relation_id,origin,evidence_id,summary_id,confidence,active) VALUES(?,?,'llm_triple',?,?,0.99,TRUE)`, supportID, relationID, oldEvidence, oldSummary)

	if err := store.ActivateVersion(ctx, docID, newVersion); err != nil {
		t.Fatalf("activate version: %v", err)
	}

	assertString := func(query, want string, args ...any) {
		t.Helper()
		var got string
		if err := store.db.QueryRowContext(ctx, query, args...).Scan(&got); err != nil {
			t.Fatalf("query failed: %v", err)
		}
		if got != want {
			t.Fatalf("got %q want %q for %s", got, want, query)
		}
	}
	assertBool := func(query string, want bool, args ...any) {
		t.Helper()
		var got bool
		if err := store.db.QueryRowContext(ctx, query, args...).Scan(&got); err != nil {
			t.Fatalf("query failed: %v", err)
		}
		if got != want {
			t.Fatalf("got %v want %v for %s", got, want, query)
		}
	}

	assertString(`SELECT lifecycle FROM document_version WHERE version_id=?`, "INACTIVE", oldVersion)
	assertString(`SELECT lifecycle FROM document_version WHERE version_id=?`, "ACTIVE", newVersion)
	assertBool(`SELECT active FROM evidence WHERE evidence_id=?`, false, oldEvidence)
	assertBool(`SELECT active FROM evidence WHERE evidence_id=?`, true, newEvidence)
	assertString(`SELECT lifecycle FROM summary WHERE summary_id=?`, "STALE", oldSummary)
	assertString(`SELECT lifecycle FROM wiki WHERE wiki_id=? AND wiki_version=1`, "STALE", wikiID)
	assertBool(`SELECT active FROM relation_evidence_support WHERE support_id=?`, false, supportID)
	assertString(`SELECT DATE_FORMAT(valid_to,'%Y-%m-%d %H:%i:%s') FROM relation_fact WHERE relation_id=?`, "2030-01-01 00:00:00", relationID)
	assertString(`SELECT lifecycle FROM relation_fact WHERE relation_id=?`, "ACTIVE", relationID)
}
