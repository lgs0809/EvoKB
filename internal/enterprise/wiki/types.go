package wiki

import (
	"context"
	"io"
	"time"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

const (
	TypeCompany       = "COMPANY"
	TypePerson        = "PERSON"
	TypeContract      = "CONTRACT"
	TypeLegalCase     = "LEGAL_CASE"
	TypeProject       = "PROJECT"
	TypeControlTopic  = "CONTROL_TOPIC"
	TypeEquityTopic   = "EQUITY_TOPIC"
	TypeGenericEntity = "GENERIC_ENTITY"
	TypeGenericTopic  = "GENERIC_TOPIC"
)

type TemplateSection struct {
	Name        string
	Description string
}

type Template struct {
	WikiType string
	Sections []TemplateSection
}

type SourceSet struct {
	Summaries []enterprisesummary.StoredSummary
}

type ProposedClaim struct {
	Section           string   `json:"section"`
	ClaimText         string   `json:"claim_text"`
	SupportingFactIDs []string `json:"supporting_fact_ids"`
}

type FactDecision struct {
	FactID       string `json:"fact_id"`
	WikiEligible bool   `json:"wiki_eligible"`
	SkipReason   string `json:"skip_reason"`
}

type Proposal struct {
	WikiType      string         `json:"wiki_type"`
	Title         string         `json:"title"`
	Claims        []ProposedClaim `json:"claims"`
	FactDecisions []FactDecision  `json:"fact_decisions"`
}

type Claim struct {
	ID                string
	WikiID            string
	WikiVersion       uint64
	Section           string
	ClaimText         string
	SupportingFactIDs []string
	EvidenceIDs       []string
	GroundingStatus   string
	GroundingReason   string
	TemporalStatus    string
}

type StoredWiki struct {
	ID                 string
	Version            uint64
	WikiType           string
	CanonicalSubjectID string
	Title              string
	ObjectKey          string
	ContentHash        string
	Lifecycle          string
	Claims             []Claim
	CreatedAt          time.Time
}

type CoverageResult struct {
	EligibleFacts int
	CoveredFacts  int
	SkippedFacts  int
	MissingFacts  []string
	Pass          bool
}

type CompileRequest struct {
	WikiType           string
	Title              string
	CanonicalSubjectID string
	SummaryIDs         []string
}

type CompileResult struct {
	Wiki     StoredWiki
	Markdown string
	Coverage CoverageResult
	Writer   string
	Judge    string
}

type Generator interface {
	Identity() string
	Generate(ctx context.Context, template Template, source SourceSet) (Proposal, error)
}

type Judge interface {
	Identity() string
	SupportsClaim(ctx context.Context, claim ProposedClaim, facts []enterprisesummary.Fact) (bool, string, error)
}

type Store interface {
	LoadSummaries(ctx context.Context, summaryIDs []string) ([]enterprisesummary.StoredSummary, error)
	NextWikiVersion(ctx context.Context, wikiID string) (uint64, error)
	SaveWiki(ctx context.Context, wiki StoredWiki) error
}

type ObjectStore interface {
	Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error
}
