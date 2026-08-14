package ingest

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestBatchLifecycleIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv("EKB_MYSQL_INTEGRATION_DSN"))
	if dsn == "" {
		t.Skip("EKB_MYSQL_INTEGRATION_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	store, err := OpenMySQL(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	batch, err := store.CreateBatch(ctx, "", "ingest-it", 1, []FileDraft{{
		RelativePath: "dept/sample.pdf", OriginalFilename: "sample.pdf", StagingPath: "/tmp/ingest-it-sample.pdf",
		SizeBytes: 42, SHA256: strings.Repeat("a", 64), LogicalName: "sample", Company: "ACME", ManualTags: []string{"it", "it"},
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer store.db.ExecContext(context.Background(), `DELETE FROM ingestion_batch WHERE batch_id=?`, batch.BatchID)
	if batch.Status != "STAGED" || len(batch.Files) != 1 || len(batch.Files[0].ManualTags) != 1 {
		t.Fatalf("batch=%+v", batch)
	}
	file := batch.Files[0]
	name := "sample-updated"
	department := "Legal"
	tags := []string{"contract", "2026"}
	updated, err := store.UpdateFile(ctx, file.FileID, FilePatch{LogicalName: &name, SourceDepartment: &department, ManualTags: &tags})
	if err != nil {
		t.Fatal(err)
	}
	if updated.LogicalName != name || updated.SourceDepartment != department || len(updated.ManualTags) != 2 {
		t.Fatalf("updated=%+v", updated)
	}
	if err := store.QueueBatch(ctx, batch.BatchID); err != nil {
		t.Fatal(err)
	}
	claimed, err := store.ClaimNext(ctx)
	if err != nil || claimed == nil || claimed.FileID != file.FileID || claimed.Attempt != 1 {
		t.Fatalf("claimed=%+v err=%v", claimed, err)
	}
	if err := store.UpdateProgress(ctx, file.FileID, "PARSING"); err != nil {
		t.Fatal(err)
	}
	if err := store.Fail(ctx, file.FileID, "IT_FAILURE", "expected integration failure"); err != nil {
		t.Fatal(err)
	}
	failed, _ := store.GetFile(ctx, file.FileID)
	if failed.Status != "FAILED" || failed.ProgressStage != "FAILED" {
		t.Fatalf("failed=%+v", failed)
	}
	if err := store.RetryFile(ctx, file.FileID); err != nil {
		t.Fatal(err)
	}
	claimed, err = store.ClaimNext(ctx)
	if err != nil || claimed == nil || claimed.Attempt != 2 {
		t.Fatalf("retry claim=%+v err=%v", claimed, err)
	}
	if err := store.Complete(ctx, file.FileID, "doc-it", "version-it"); err != nil {
		t.Fatal(err)
	}
	final, err := store.GetBatch(ctx, batch.BatchID)
	if err != nil {
		t.Fatal(err)
	}
	if final.Status != "SUCCEEDED" || final.SucceededCount != 1 || final.FailedCount != 0 {
		t.Fatalf("final=%+v", final)
	}
}
