package admin

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	enterpriseingest "github.com/xoai/sage-wiki/internal/enterprise/ingest"
)

func TestTaskScopeIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv("EKB_MYSQL_INTEGRATION_DSN"))
	if dsn == "" {
		t.Skip("EKB_MYSQL_INTEGRATION_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	adminStore, err := OpenMySQL(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer adminStore.Close()
	batchStore, err := enterpriseingest.OpenMySQL(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer batchStore.Close()

	userA, userB := uuid.NewString(), uuid.NewString()
	usernameA, usernameB := "task-scope-"+userA[:8], "task-scope-"+userB[:8]
	passwordHash := strings.Repeat("x", 60)
	for _, user := range []struct{ id, name string }{{userA, usernameA}, {userB, usernameB}} {
		if _, err := adminStore.db.ExecContext(ctx, `INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role,status) VALUES(?,?,?,?,'USER','ACTIVE')`, user.id, user.name, user.name, passwordHash); err != nil {
			t.Fatal(err)
		}
	}
	defer func() {
		_, _ = adminStore.db.ExecContext(context.Background(), `DELETE FROM enterprise_user WHERE user_id IN (?,?)`, userA, userB)
	}()

	batchA, err := batchStore.CreateBatch(ctx, userA, usernameA, 1, []enterpriseingest.FileDraft{{RelativePath: "a.pdf", OriginalFilename: "a.pdf", StagingPath: "/tmp/a.pdf", SizeBytes: 1, SHA256: strings.Repeat("a", 64), LogicalName: "task-a"}})
	if err != nil {
		t.Fatal(err)
	}
	batchB, err := batchStore.CreateBatch(ctx, userB, usernameB, 1, []enterpriseingest.FileDraft{{RelativePath: "b.pdf", OriginalFilename: "b.pdf", StagingPath: "/tmp/b.pdf", SizeBytes: 1, SHA256: strings.Repeat("b", 64), LogicalName: "task-b"}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		_, _ = adminStore.db.ExecContext(context.Background(), `DELETE FROM ingestion_batch WHERE batch_id IN (?,?)`, batchA.BatchID, batchB.BatchID)
	}()

	userTasks, userTotal, err := adminStore.ListTasksPage(ctx, PageQuery{Page: 1, PageSize: 20, ActorID: userA, Admin: false})
	if err != nil {
		t.Fatal(err)
	}
	if userTotal != 1 || len(userTasks) != 1 || userTasks[0].LogicalName != "task-a" || userTasks[0].Source != "INGEST" {
		t.Fatalf("user tasks=%+v total=%d", userTasks, userTotal)
	}
	adminTasks, adminTotal, err := adminStore.ListTasksPage(ctx, PageQuery{Page: 1, PageSize: 100, Admin: true, Query: "task-"})
	if err != nil {
		t.Fatal(err)
	}
	seenA, seenB := false, false
	for _, task := range adminTasks {
		seenA = seenA || task.LogicalName == "task-a"
		seenB = seenB || task.LogicalName == "task-b"
	}
	if adminTotal < 2 || !seenA || !seenB {
		t.Fatalf("admin tasks=%+v total=%d", adminTasks, adminTotal)
	}
}
