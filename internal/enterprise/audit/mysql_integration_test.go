package audit

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"
)

func TestAuditRecordAndFilterIntegration(t *testing.T) {
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
	action := "audit.it." + time.Now().UTC().Format("150405.000000")
	if err := store.Record(ctx, Record{ActorUsername: "audit-it", ActorRole: "ADMIN", Action: action, TargetType: "document", TargetID: "doc-it", TargetName: "Contract", Outcome: "SUCCESS", RequestID: "request-it", IPAddress: "127.0.0.1", Details: map[string]any{"count": 1}}); err != nil {
		t.Fatal(err)
	}
	defer store.db.ExecContext(context.Background(), `DELETE FROM audit_event WHERE action=?`, action)
	items, total, err := store.List(ctx, ListOptions{Page: 1, PageSize: 10, Actor: "audit-it", Action: action, Outcome: "SUCCESS"})
	if err != nil {
		t.Fatal(err)
	}
	if total != 1 || len(items) != 1 || items[0].TargetID != "doc-it" || !strings.Contains(string(items[0].Details), `"count": 1`) {
		t.Fatalf("items=%+v total=%d", items, total)
	}
}
