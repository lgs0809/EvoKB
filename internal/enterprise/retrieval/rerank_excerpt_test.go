package retrieval

import (
	"strings"
	"testing"
)

func TestBoundedRerankTextCentersOnQuery(t *testing.T) {
	text := strings.Repeat("前", 5000) + "重点工程信号评估平台" + strings.Repeat("后", 5000)
	got := boundedRerankText(text, "重点工程信号评估平台", 2000)
	if len([]rune(got)) > 2000 {
		t.Fatalf("excerpt too large: %d", len([]rune(got)))
	}
	if !strings.Contains(got, "重点工程信号评估平台") {
		t.Fatal("query context was not preserved")
	}
}

func TestBoundedRerankTextKeepsShortDocument(t *testing.T) {
	const text = "short evidence"
	if got := boundedRerankText(text, "evidence", 2000); got != text {
		t.Fatalf("short document changed: %q", got)
	}
}
