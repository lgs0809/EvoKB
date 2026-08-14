package promotion

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	"github.com/xoai/sage-wiki/internal/enterprise/retrieval"
)

func TestPromotionUsesOnlyDistinctDeliveredSearches(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv("EKB_MYSQL_INTEGRATION_DSN"))
	if dsn == "" {
		t.Skip("EKB_MYSQL_INTEGRATION_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	store, err := OpenMySQL(ctx, dsn, 3)
	if err != nil {
		t.Fatalf("open promotion store: %v", err)
	}
	defer store.Close()

	docID := newUUID()
	versionID := newUUID()
	searchIDs := []string{newUUID(), newUUID(), newUUID()}
	if _, err := store.db.ExecContext(ctx, `INSERT INTO document(document_id,logical_name,status) VALUES(?,?,'ACTIVE')`, docID, "promotion-it-"+docID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.ExecContext(ctx, `INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key,activated_at) VALUES(?,?,1,'ACTIVE','promotion.pdf',1,?,?,CURRENT_TIMESTAMP(6))`, versionID, docID, strings.Repeat("9", 64), "raw/"+versionID+"/promotion.pdf"); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_, _ = store.db.ExecContext(context.Background(), `DELETE FROM distill_recommendation WHERE version_id=?`, versionID)
		_, _ = store.db.ExecContext(context.Background(), `DELETE FROM effective_version_hit WHERE version_id=?`, versionID)
		for _, searchID := range searchIDs {
			_, _ = store.db.ExecContext(context.Background(), `DELETE FROM search_result WHERE search_id=?`, searchID)
			_, _ = store.db.ExecContext(context.Background(), `DELETE FROM search_record WHERE search_id=?`, searchID)
		}
		_, _ = store.db.ExecContext(context.Background(), `DELETE FROM document_version WHERE version_id=?`, versionID)
		_, _ = store.db.ExecContext(context.Background(), `DELETE FROM document WHERE document_id=?`, docID)
	}()

	makeResult := func(searchID string) *retrieval.SearchResult {
		return &retrieval.SearchResult{SearchID: searchID, Results: []retrieval.Result{{
			Candidate: retrieval.Candidate{ID: "raw-result", ResultType: retrieval.TypeRaw, Knowledge: "delivered", Score: 0.9},
			Evidence: []enterpriseevidence.Resolved{{EvidenceID: newUUID(), DocumentID: docID, VersionID: versionID, ObjectKey: "raw/source.pdf", RawContent: "source"}},
		}}}
	}
	for i := 0; i < 2; i++ {
		if err := store.RecordDeliveredSearch(ctx, "query", raw.SearchFilters{}, makeResult(searchIDs[i])); err != nil {
			t.Fatalf("record delivered search %d: %v", i+1, err)
		}
	}
	var count int
	if err := store.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM distill_recommendation WHERE version_id=? AND reason='RUNTIME_EFFECTIVE_HIT'`, versionID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatalf("runtime recommendation appeared before threshold: %d", count)
	}
	if err := store.RecordDeliveredSearch(ctx, "query", raw.SearchFilters{}, makeResult(searchIDs[2])); err != nil {
		t.Fatalf("record third delivered search: %v", err)
	}
	var hits int
	if err := store.db.QueryRowContext(ctx, `SELECT distinct_effective_hits FROM distill_recommendation WHERE version_id=? AND reason='RUNTIME_EFFECTIVE_HIT'`, versionID).Scan(&hits); err != nil {
		t.Fatal(err)
	}
	if hits != 3 {
		t.Fatalf("got %d effective hits, want 3", hits)
	}
	if err := store.RecommendManual(ctx, versionID); err != nil {
		t.Fatalf("manual recommendation: %v", err)
	}
	if err := store.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM distill_recommendation WHERE version_id=? AND reason='USER_MARKED' AND status='RECOMMENDED'`, versionID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("manual recommendation missing: %d", count)
	}
}
