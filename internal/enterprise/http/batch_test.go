package enterprisehttp

import (
	"archive/zip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSafeRelativePath(t *testing.T) {
	cases := map[string]string{
		"folder/report.pdf":             "folder/report.pdf",
		`folder\\nested\\contract.docx`: "folder/nested/contract.docx",
		"../../secret.pdf":              "",
		"folder/../../../secret.pdf":    "",
		"/absolute/report.pdf":          "absolute/report.pdf",
	}
	for input, want := range cases {
		if got := safeRelativePath(input); got != want {
			t.Fatalf("safeRelativePath(%q)=%q want=%q", input, got, want)
		}
	}
}

func TestExtractZIPDraftsRejectsTraversalAndUnsupported(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "batch.zip")
	file, err := os.Create(archive)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(file)
	entries := map[string]string{
		"dept/report.pdf": "pdf-data",
		"../escape.pdf":   "escape",
		"dept/tool.exe":   "binary",
	}
	for name, body := range entries {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}

	stage := filepath.Join(root, "stage")
	if err := os.Mkdir(stage, 0o700); err != nil {
		t.Fatal(err)
	}
	drafts, rejected, used, err := extractZIPDrafts(archive, stage, batchDefaults{Company: "ACME"}, 10, 1024)
	if err != nil {
		t.Fatal(err)
	}
	if len(drafts) != 1 || drafts[0].RelativePath != "dept/report.pdf" || drafts[0].Company != "ACME" {
		t.Fatalf("drafts=%+v", drafts)
	}
	if used != int64(len("pdf-data")) {
		t.Fatalf("used=%d", used)
	}
	joined := strings.Join(rejected, "\n")
	if !strings.Contains(joined, "escape.pdf") || !strings.Contains(joined, "tool.exe") {
		t.Fatalf("rejected=%v", rejected)
	}
	if _, err := os.Stat(filepath.Join(root, "escape.pdf")); !os.IsNotExist(err) {
		t.Fatalf("zip traversal escaped staging directory")
	}
}

func TestExtractZIPDraftsEnforcesExpandedLimit(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "batch.zip")
	file, _ := os.Create(archive)
	zw := zip.NewWriter(file)
	w, _ := zw.Create("large.pdf")
	_, _ = w.Write([]byte(strings.Repeat("x", 128)))
	_ = zw.Close()
	_ = file.Close()
	stage := filepath.Join(root, "stage")
	_ = os.Mkdir(stage, 0o700)
	if _, _, _, err := extractZIPDrafts(archive, stage, batchDefaults{}, 10, 64); err == nil {
		t.Fatal("expected expanded size limit error")
	}
}
