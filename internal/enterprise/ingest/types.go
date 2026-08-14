package ingest

import "time"

type Batch struct {
	BatchID           string     `json:"batch_id"`
	CreatedBy         string     `json:"created_by,omitempty"`
	CreatedByUsername string     `json:"created_by_username"`
	Status            string     `json:"status"`
	SourceCount       int        `json:"source_count"`
	FileCount         int        `json:"file_count"`
	StagedCount       int        `json:"staged_count"`
	QueuedCount       int        `json:"queued_count"`
	RunningCount      int        `json:"running_count"`
	SucceededCount    int        `json:"succeeded_count"`
	FailedCount       int        `json:"failed_count"`
	CreatedAt         time.Time  `json:"created_at"`
	UpdatedAt         time.Time  `json:"updated_at"`
	StartedAt         *time.Time `json:"started_at,omitempty"`
	FinishedAt        *time.Time `json:"finished_at,omitempty"`
	Files             []File     `json:"files,omitempty"`
}

type File struct {
	FileID                    string     `json:"file_id"`
	BatchID                   string     `json:"batch_id"`
	RelativePath              string     `json:"relative_path"`
	OriginalFilename          string     `json:"original_filename"`
	StagingPath               string     `json:"-"`
	SizeBytes                 int64      `json:"size_bytes"`
	SHA256                    string     `json:"sha256"`
	LogicalName               string     `json:"logical_name"`
	SourceDepartment          string     `json:"source_department,omitempty"`
	MaterialCategory          string     `json:"material_category,omitempty"`
	Company                   string     `json:"company,omitempty"`
	DocumentType              string     `json:"document_type,omitempty"`
	DocumentDate              *time.Time `json:"document_date,omitempty"`
	ManualTags                []string   `json:"manual_tags,omitempty"`
	Status                    string     `json:"status"`
	ProgressStage             string     `json:"progress_stage"`
	Attempt                   int        `json:"attempt"`
	DocumentID                string     `json:"document_id,omitempty"`
	VersionID                 string     `json:"version_id,omitempty"`
	ExpectedBehavior          string     `json:"expected_behavior,omitempty"`
	ExistingDocumentID        string     `json:"existing_document_id,omitempty"`
	ExistingVersionNo         uint64     `json:"existing_version_no,omitempty"`
	DuplicateDocumentID       string     `json:"duplicate_document_id,omitempty"`
	DuplicateVersionID        string     `json:"duplicate_version_id,omitempty"`
	DuplicateLogicalName      string     `json:"duplicate_logical_name,omitempty"`
	DuplicateVersionLifecycle string     `json:"duplicate_version_lifecycle,omitempty"`
	ErrorCode                 string     `json:"error_code,omitempty"`
	ErrorMessage              string     `json:"error_message,omitempty"`
	CreatedAt                 time.Time  `json:"created_at"`
	UpdatedAt                 time.Time  `json:"updated_at"`
	StartedAt                 *time.Time `json:"started_at,omitempty"`
	FinishedAt                *time.Time `json:"finished_at,omitempty"`
}

type FileDraft struct {
	RelativePath     string
	OriginalFilename string
	StagingPath      string
	SizeBytes        int64
	SHA256           string
	LogicalName      string
	SourceDepartment string
	MaterialCategory string
	Company          string
	DocumentType     string
	DocumentDate     *time.Time
	ManualTags       []string
}

type FilePatch struct {
	LogicalName       *string    `json:"logical_name,omitempty"`
	SourceDepartment  *string    `json:"source_department,omitempty"`
	MaterialCategory  *string    `json:"material_category,omitempty"`
	Company           *string    `json:"company,omitempty"`
	DocumentType      *string    `json:"document_type,omitempty"`
	DocumentDate      *time.Time `json:"document_date,omitempty"`
	ClearDocumentDate bool       `json:"clear_document_date,omitempty"`
	ManualTags        *[]string  `json:"manual_tags,omitempty"`
}

type ListOptions struct {
	Page     int
	PageSize int
	Status   string
	Query    string
	ActorID  string
	Admin    bool
}
