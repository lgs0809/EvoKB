package raw

import (
	"context"
	"io"
	"time"
)

// Document and DocumentVersion model the enterprise file lifecycle. A document
// version becoming inactive must not be interpreted as the end of any business
// relation validity interval.
type Document struct {
	ID          string
	LogicalName string
	SourcePath  string
}

type DocumentVersion struct {
	ID                string
	DocumentID        string
	VersionNo         uint64
	Lifecycle         string
	OriginalFilename  string
	MediaType         string
	SizeBytes         int64
	SHA256            string
	OriginalObjectKey string
	MarkdownObjectKey string
	StructuredKey     string
	ParserName        string
	ParserVersion     string
}

type Metadata struct {
	SourceDepartment string
	MaterialCategory string
	ManualTags       []string
	Company          string
	DocumentDate     *time.Time
	DocumentType     string
}

type ParsedDocument struct {
	Markdown      string
	MiddleJSON    string
	ContentList   string
	ParserName    string
	ParserVersion string
	Blocks        []ParsedBlock
}

type ParsedBlock struct {
	Type      string
	Text      string
	TextLevel int
	Page      int
	BBox      []float64
	ImagePath string
	TableHTML string
	TableBody string
	Raw       map[string]any
}

type Chunk struct {
	ID             string
	DocumentID     string
	VersionID      string
	Content        string
	Normalized     string
	HeadingPath    []string
	SourceBlockIDs []string
	PageStart      *int
	PageEnd        *int
	ContentHash    string
	TokenCount     int
	Embedding      []float32
	Metadata       Metadata
}

type RawBlock struct {
	ID             string
	VersionID      string
	BlockNo        int
	BlockType      string
	Content        string
	Normalized     string
	HeadingPath    []string
	PageStart      *int
	PageEnd        *int
	BBox           []float64
	SourceBlockIDs []string
	ContentHash    string
	TokenCount     int
}

type Evidence struct {
	ID         string
	DocumentID string
	VersionID  string
	BlockID    string
	ObjectKey  string
	Location   map[string]any
	Content    string
	Hash       string
}

type SearchFilters struct {
	SourceDepartment string
	MaterialCategory string
	Company          string
	DocumentType     string
	VersionIDs       []string
}

type Candidate struct {
	ChunkID       string
	DocumentID    string
	VersionID     string
	Content       string
	HeadingPath   []string
	PageStart     *int
	PageEnd       *int
	Score         float64
	EvidenceIDs   []string
	KnowledgeType string
}

type SearchResult struct {
	SearchID string
	Results  []Candidate
}

type ObjectStore interface {
	Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error
	Get(ctx context.Context, key string) (io.ReadCloser, error)
	Stat(ctx context.Context, key string) (ObjectInfo, error)
	PresignGet(ctx context.Context, key string, expiry time.Duration) (string, error)
}

type ObjectInfo struct {
	Key         string
	Size        int64
	ContentType string
	ETag        string
}

type MetadataStore interface {
	FindDocumentByLogicalName(ctx context.Context, logicalName string) (*Document, error)
	FindVersionBySHA(ctx context.Context, documentID, sha256 string) (*DocumentVersion, error)
	CurrentActiveVersion(ctx context.Context, documentID string) (*DocumentVersion, error)
	NextVersionNo(ctx context.Context, documentID string) (uint64, error)
	CreatePendingVersion(ctx context.Context, doc Document, version DocumentVersion, md Metadata) error
	ResetFailedVersion(ctx context.Context, versionID string, md Metadata) error
	SaveParsed(ctx context.Context, versionID, markdownKey, structuredKey, parserName, parserVersion string, blocks []RawBlock, evidence []Evidence) error
	ActivateVersion(ctx context.Context, documentID, versionID string) error
	MarkVersionFailed(ctx context.Context, versionID, code, message string) error
	ResolveEvidence(ctx context.Context, evidenceIDs []string) ([]Evidence, error)
	Close() error
}

type Parser interface {
	Parse(ctx context.Context, filename string, r io.Reader) (*ParsedDocument, error)
}

type Embedder interface {
	Embed(ctx context.Context, texts []string) ([][]float32, error)
	Dimensions() int
}

type Reranker interface {
	Rerank(ctx context.Context, query string, docs []string) ([]float64, error)
}

type SearchStore interface {
	EnsureRawIndex(ctx context.Context, dimensions int) error
	IndexRaw(ctx context.Context, chunks []Chunk, evidenceByChunk map[string][]string) error
	ActivateVersion(ctx context.Context, documentID, versionID string) error
	DeactivateVersion(ctx context.Context, versionID string) error
	DeleteVersion(ctx context.Context, versionID string) error
	SearchBM25(ctx context.Context, query string, filters SearchFilters, limit int) ([]Candidate, error)
	SearchVector(ctx context.Context, vector []float32, filters SearchFilters, limit int) ([]Candidate, error)
}
