package enterprisehttp

import "testing"

func TestSafeUploadFilename(t *testing.T) {
	cases := map[string]string{
		"report.pdf":                     "report.pdf",
		"../../secret.pdf":               "secret.pdf",
		`C:\\Users\\demo\\scan.pdf`:      "scan.pdf",
		"  contract.docx  ":              "contract.docx",
		"../folder/../presentation.pptx": "presentation.pptx",
	}
	for input, want := range cases {
		if got := safeUploadFilename(input); got != want {
			t.Fatalf("safeUploadFilename(%q)=%q want=%q", input, got, want)
		}
	}
}

func TestSafeUploadFilenameRejectsEmptyBasename(t *testing.T) {
	for _, input := range []string{"", ".", ".."} {
		if got := safeUploadFilename(input); got != "" {
			t.Fatalf("safeUploadFilename(%q)=%q want empty", input, got)
		}
	}
}
