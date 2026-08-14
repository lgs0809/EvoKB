package summary

import "github.com/xoai/sage-wiki/internal/enterprise/raw"

type SourceVersion struct {
	DocumentID                 string
	VersionID                  string
	LogicalName                string
	ManualDocumentTypeOverride string
	AutoDocumentType           string
	Metadata                   raw.Metadata
	Blocks                     []raw.RawBlock
	Evidence                   []raw.Evidence
}
