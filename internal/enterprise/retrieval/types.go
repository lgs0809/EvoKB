package retrieval

import enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"

const (
	TypeRaw = "RAW"
	TypeSummary = "SUMMARY"
	TypeWiki = "WIKI"
	TypeGraph = "GRAPH"
)

type Candidate struct {
	ID string
	ResultType string
	Knowledge string
	LaneScore float64
	Score float64
	EvidenceIDs []string
}

type Result struct {
	Candidate Candidate
	Evidence []enterpriseevidence.Resolved
}

type SearchResult struct {
	SearchID string
	Results []Result
}

type Quotas struct { Raw, Summary, Wiki, Graph int }
