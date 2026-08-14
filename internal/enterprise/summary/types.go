package summary

import (
	"context"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

const (
	DocumentTypeAnnualReport       = "年度/半年度报告"
	DocumentTypeAuditReport        = "审计报告"
	DocumentTypeContract           = "合同/协议"
	DocumentTypeLegal              = "诉讼/仲裁/判决"
	DocumentTypePolicy             = "章程/制度/政策"
	DocumentTypeProject            = "项目/尽调/业务材料"
	DocumentTypeEquityControl      = "股权/控制权变更"
	DocumentTypeResolution         = "董事会/股东会决议"
	DocumentTypeFinancingGuarantee = "融资/担保"
	DocumentTypeLicenseIP          = "资质/许可/知识产权"
	DocumentTypeGeneralMixed       = "General / Mixed"
)

const (
	StructureSingle = "single"
	StructureBundle = "bundle"
	StructureMixed  = "mixed"
)

var AllowedDocumentTypes = []string{
	DocumentTypeAnnualReport,
	DocumentTypeAuditReport,
	DocumentTypeContract,
	DocumentTypeLegal,
	DocumentTypePolicy,
	DocumentTypeProject,
	DocumentTypeEquityControl,
	DocumentTypeResolution,
	DocumentTypeFinancingGuarantee,
	DocumentTypeLicenseIP,
	DocumentTypeGeneralMixed,
}

type TemplateSection struct {
	Name        string
	Description string
	Required    bool
}

type Template struct {
	DocumentType string
	Sections     []TemplateSection
}

type UnitProposal struct {
	Title        string `json:"title"`
	DocumentType string `json:"document_type"`
	StartBlockNo int    `json:"start_block_no"`
	EndBlockNo   int    `json:"end_block_no"`
}

type Classification struct {
	DocumentType string         `json:"document_type"`
	StructureType string        `json:"structure_type"`
	Units         []UnitProposal `json:"units"`
}

type DocumentUnit struct {
	ID            string
	VersionID     string
	UnitNo        int
	Title         string
	StructureType string
	DocumentType  string
	StartBlockNo  int
	EndBlockNo    int
}

type ProposedFact struct {
	Section          string   `json:"section"`
	FactText         string   `json:"fact_text"`
	EvidenceBlockIDs []string `json:"evidence_block_ids"`
}

type Fact struct {
	ID               string
	SummaryID        string
	Section          string
	FactText         string
	EvidenceBlockIDs []string
	EvidenceIDs      []string
	GroundingStatus  string
	GroundingReason  string
}

type MissingFactCandidate struct {
	Description    string   `json:"missing_fact_candidate"`
	Section        string   `json:"section"`
	SourceBlockIDs []string `json:"source_block_ids"`
}

type UnitSource struct {
	Unit            DocumentUnit
	Blocks          []raw.RawBlock
	EvidenceByBlock map[string]raw.Evidence
}

type StoredSummary struct {
	ID              string
	UnitID          string
	VersionID       string
	ObjectKey       string
	ContentHash     string
	GroundingStatus string
	CoverageStatus  string
	Lifecycle       string
	Model           string
	Facts           []Fact
	CreatedAt       time.Time
}

type CompileResult struct {
	Summary        StoredSummary
	Markdown       string
	CoverageRepair int
}

type CompilerModel interface {
	Identity() string
	Classify(ctx context.Context, blocks []raw.RawBlock, manualDocumentType string) (Classification, error)
	ExtractFacts(ctx context.Context, template Template, source UnitSource, focusSections []string, missing []MissingFactCandidate) ([]ProposedFact, error)
}

type QualityJudge interface {
	Identity() string
	SupportsFact(ctx context.Context, fact Fact, evidence []raw.Evidence) (bool, string, error)
	CheckCoverage(ctx context.Context, template Template, source UnitSource, facts []Fact) ([]MissingFactCandidate, error)
}
