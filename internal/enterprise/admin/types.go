package admin

import (
	"encoding/json"
	"time"
)

type DocumentVersionView struct {
	DocumentID           string     `json:"document_id"`
	LogicalName          string     `json:"logical_name"`
	VersionID            string     `json:"version_id"`
	VersionNo            uint64     `json:"version_no"`
	Lifecycle            string     `json:"lifecycle"`
	OriginalFilename     string     `json:"original_filename"`
	SourceDepartment     string     `json:"source_department,omitempty"`
	MaterialCategory     string     `json:"material_category,omitempty"`
	Company              string     `json:"company,omitempty"`
	DocumentType         string     `json:"document_type,omitempty"`
	DocumentDate         *time.Time `json:"document_date,omitempty"`
	ParsedAt             *time.Time `json:"parsed_at,omitempty"`
	SummaryCount         int        `json:"summary_count"`
	GroundedSummaries    int        `json:"grounded_summaries"`
	CoveredSummaries     int        `json:"covered_summaries"`
	Recommendation       string     `json:"recommendation,omitempty"`
	RecommendationReason string     `json:"recommendation_reason,omitempty"`
	EffectiveHits        int        `json:"effective_hits"`
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

type OverviewView struct {
	Documents           int `json:"documents"`
	Versions            int `json:"versions"`
	ActiveVersions      int `json:"active_versions"`
	PendingVersions     int `json:"pending_versions"`
	FailedVersions      int `json:"failed_versions"`
	RawBlocks           int `json:"raw_blocks"`
	ActiveEvidence      int `json:"active_evidence"`
	ActiveSummaries     int `json:"active_summaries"`
	StaleSummaries      int `json:"stale_summaries"`
	GroundedSummaries   int `json:"grounded_summaries"`
	CoveredSummaries    int `json:"covered_summaries"`
	ActiveWikis         int `json:"active_wikis"`
	StaleWikis          int `json:"stale_wikis"`
	ActiveRelations     int `json:"active_relations"`
	ActiveGraphSupports int `json:"active_graph_supports"`
	EffectiveHits       int `json:"effective_hits"`
	RecommendedSources  int `json:"recommended_sources"`
	PendingTasks        int `json:"pending_tasks"`
	RunningTasks        int `json:"running_tasks"`
	FailedTasks         int `json:"failed_tasks"`
}

type CompileTaskView struct {
	TaskID        string     `json:"task_id"`
	Source        string     `json:"source,omitempty"`
	BatchID       string     `json:"batch_id,omitempty"`
	FileID        string     `json:"file_id,omitempty"`
	VersionID     string     `json:"version_id,omitempty"`
	LogicalName   string     `json:"logical_name,omitempty"`
	TaskType      string     `json:"task_type"`
	Status        string     `json:"status"`
	ProgressStage string     `json:"progress_stage,omitempty"`
	Attempt       uint64     `json:"attempt"`
	ErrorCode     string     `json:"error_code,omitempty"`
	ErrorMessage  string     `json:"error_message,omitempty"`
	StartedAt     *time.Time `json:"started_at,omitempty"`
	FinishedAt    *time.Time `json:"finished_at,omitempty"`
	CreatedAt     time.Time  `json:"created_at"`
}

type EvidenceListView struct {
	EvidenceID       string          `json:"evidence_id"`
	DocumentID       string          `json:"document_id"`
	LogicalName      string          `json:"logical_name"`
	VersionID        string          `json:"version_id"`
	VersionNo        uint64          `json:"version_no"`
	OriginalFilename string          `json:"original_filename"`
	BlockID          string          `json:"block_id"`
	BlockType        string          `json:"block_type"`
	RawLocation      json.RawMessage `json:"raw_location"`
	Excerpt          string          `json:"excerpt"`
	Active           bool            `json:"active"`
	CreatedAt        time.Time       `json:"created_at"`
}
