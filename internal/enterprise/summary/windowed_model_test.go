package summary

import (
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

func TestRawWindowsPreserveUTF8AndAllContent(t *testing.T) {
	content := strings.Repeat("黑龙江重点工程信号评估平台", 1200)
	blocks := []raw.RawBlock{{ID: "b1", VersionID: "v1", BlockNo: 0, BlockType: "text", Content: content}}
	windows := rawWindows(blocks, 4096)
	if len(windows) < 2 {
		t.Fatalf("window count=%d, want multiple windows", len(windows))
	}
	for i, window := range windows {
		if !utf8.ValidString(window) {
			t.Fatalf("window %d is invalid UTF-8", i)
		}
	}
	var recovered strings.Builder
	for _, window := range windows {
		start := strings.Index(window, ">\n")
		end := strings.LastIndex(window, "\n</raw_block>")
		if start < 0 || end < start {
			t.Fatalf("unexpected window framing: %q", window[:min(len(window), 80)])
		}
		recovered.WriteString(window[start+2 : end])
	}
	if recovered.String() != content {
		t.Fatalf("windowing lost source content: got=%d bytes want=%d", recovered.Len(), len(content))
	}
}

func TestClassificationOutlineRepresentsFirstAndLastBlock(t *testing.T) {
	blocks := []raw.RawBlock{
		{ID: "first", BlockNo: 0, BlockType: "text", HeadingPath: []string{"开头章节"}, Content: "开头内容"},
		{ID: "middle", BlockNo: 1, BlockType: "text", Content: strings.Repeat("中间", 500)},
		{ID: "last", BlockNo: 2, BlockType: "text", HeadingPath: []string{"末尾章节"}, Content: "末尾合同附件"},
	}
	outline := classificationOutline(blocks)
	for _, marker := range []string{"id=first", "id=last", "开头章节", "末尾合同附件"} {
		if !strings.Contains(outline, marker) {
			t.Fatalf("classification outline omitted %q: %s", marker, outline)
		}
	}
}
