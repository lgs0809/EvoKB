package admin

import "time"

type DocumentVersionView struct {
	DocumentID        string     `json:"document_id"`
	LogicalName       string     `json:"logical_name"`
	VersionID         string     `json:"version_id"`
	VersionNo         uint64     `json:"version_no"`
	Lifecycle         string     `json:"lifecycle"`
	OriginalFilename  string     `json:"original_filename"`
	SourceDepartment  string     `json:"source_department,omitempty"`
	MaterialCategory  string     `json:"material_category,omitempty"`
	Company           string     `json:"company,omitempty"`
	DocumentType      string     `json:"document_type,omitempty"`
	DocumentDate      *time.Time `json:"document_date,omitempty"`
	ParsedAt          *time.Time `json:"parsed_at,omitempty"`
	SummaryCount      int        `json:"summary_count"`
	GroundedSummaries int        `json:"grounded_summaries"`
	CoveredSummaries  int        `json:"covered_summaries"`
	Recommendation    string     `json:"recommendation,omitempty"`
	RecommendationReason string  `json:"recommendation_reason,omitempty"`
	EffectiveHits     int        `json:"effective_hits"`
}

type WikiView struct {
	WikiID             string      `json:"wiki_id"`
	WikiVersion        uint64      `json:"wiki_version"`
	WikiType           string      `json:"wiki_type"`
	CanonicalSubjectID string      `json:"canonical_subject_id,omitempty"`
	Title              string      `json:"title"`
	Lifecycle          string      `json:"lifecycle"`
	Claims             []ClaimView `json:"claims,omitempty"`
}

type ClaimView struct {
	ClaimID         string   `json:"claim_id"`
	Section         string   `json:"section"`
	ClaimText       string   `json:"claim_text"`
	GroundingStatus string   `json:"grounding_status"`
	TemporalStatus  string   `json:"temporal_status"`
	FactIDs         []string `json:"fact_ids"`
	EvidenceIDs     []string `json:"evidence_ids"`
}
