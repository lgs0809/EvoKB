package graph

import (
	"context"
	"time"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

const (
	EntityCompany      = "COMPANY"
	EntityPerson       = "PERSON"
	EntityContract     = "CONTRACT"
	EntityLegalCase    = "LEGAL_CASE"
	EntityProject      = "PROJECT"
	EntityOrganization = "ORGANIZATION"
	EntityAgency       = "GOVERNMENT_AGENCY"
	EntityProduct      = "PRODUCT"
	EntityLocation     = "LOCATION"
	EntityLicense      = "LICENSE"
	EntityIP           = "INTELLECTUAL_PROPERTY"
	EntityPolicy       = "POLICY"
	EntityEvent        = "EVENT"
	EntityTopic        = "TOPIC"
	EntityGeneric      = "GENERIC"
)

const (
	RelationActualControllerOf = "ACTUAL_CONTROLLER_OF"
	RelationControls           = "CONTROLS"
	RelationShareholderOf      = "SHAREHOLDER_OF"
	RelationSubsidiaryOf       = "SUBSIDIARY_OF"
	RelationParentOf           = "PARENT_OF"
	RelationDirectorOf         = "DIRECTOR_OF"
	RelationLegalRepOf         = "LEGAL_REPRESENTATIVE_OF"
	RelationPartyToContract    = "PARTY_TO_CONTRACT"
	RelationSignsWith          = "SIGNS_WITH"
	RelationGuarantees         = "GUARANTEES"
	RelationBorrowsFrom        = "BORROWS_FROM"
	RelationInvestsIn          = "INVESTS_IN"
	RelationInvolvedInCase     = "INVOLVED_IN_CASE"
	RelationOwnsIP             = "OWNS_IP"
	RelationHoldsLicense       = "HOLDS_LICENSE"
	RelationParticipatesIn     = "PARTICIPATES_IN_PROJECT"
	RelationApproves           = "APPROVES"
	RelationSuppliesTo         = "SUPPLIES_TO"
	RelationCustomerOf         = "CUSTOMER_OF"
	RelationRelatedTo          = "RELATED_TO"
)

const (
	OriginLLMTriple    = "llm_triple"
	OriginWikiKeyword  = "wiki_keyword"
	OriginAliasDerived = "alias_derived"
)

type ProposedEntity struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

type ProposedRelation struct {
	SourceName        string   `json:"source_name"`
	SourceType        string   `json:"source_type"`
	RelationType      string   `json:"relation_type"`
	TargetName        string   `json:"target_name"`
	TargetType        string   `json:"target_type"`
	SupportingFactIDs []string `json:"supporting_fact_ids"`
	ValidFrom         string   `json:"valid_from"`
	ValidTo           string   `json:"valid_to"`
	Confidence        float64  `json:"confidence"`
}

type FactDecision struct {
	FactID     string `json:"fact_id"`
	GraphEligible bool   `json:"graph_eligible"`
	SkipReason string `json:"skip_reason"`
}

type Extraction struct {
	Entities      []ProposedEntity   `json:"entities"`
	Relations     []ProposedRelation `json:"relations"`
	FactDecisions []FactDecision     `json:"fact_decisions"`
}

type CanonicalEntity struct {
	ID           string
	CanonicalName string
	EntityType    string
	CanonicalKey string
}

type RelationFact struct {
	ID             string
	SourceEntityID string
	RelationType   string
	TargetEntityID string
	ValidFrom      *time.Time
	ValidTo        *time.Time
	ConflictStatus string
	Lifecycle      string
}

type EvidenceSupport struct {
	ID         string
	RelationID string
	Origin     string
	EvidenceID string
	SummaryID  string
	WikiID     string
	WikiVersion *uint64
	Confidence float64
	Active     bool
}

type EligibleFact struct {
	FactID      string
	Eligible    bool
	SkipReason  string
	RelationIDs []string
}

type CoverageResult struct {
	EligibleFacts int
	CoveredFacts  int
	SkippedFacts  int
	MissingFacts  []string
	Pass          bool
}

type CompileResult struct {
	SummaryID string
	Entities  []CanonicalEntity
	Relations []RelationFact
	Supports  []EvidenceSupport
	Coverage  CoverageResult
	Extractor string
}

type Extractor interface {
	Identity() string
	Extract(ctx context.Context, source enterprisesummary.StoredSummary) (Extraction, error)
}

type Store interface {
	LoadGroundedSummary(ctx context.Context, summaryID string) (enterprisesummary.StoredSummary, error)
	ResolveEntity(ctx context.Context, proposed ProposedEntity) (CanonicalEntity, error)
	UpsertRelation(ctx context.Context, relation RelationFact) (RelationFact, error)
	AddSupport(ctx context.Context, support EvidenceSupport) (EvidenceSupport, error)
}

type Projector interface {
	EnsureSchema(ctx context.Context) error
	UpsertEntity(ctx context.Context, entity CanonicalEntity) error
	UpsertRelation(ctx context.Context, relation RelationFact, supports []EvidenceSupport) error
}
