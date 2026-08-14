package retrieval

import (
	"context"
	"testing"

	enterprisegraph "github.com/xoai/sage-wiki/internal/enterprise/graph"
	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type graphSearcherStub struct {
	depth  int
	expand int
	hits   []enterprisegraph.SearchHit
}

func (s *graphSearcherStub) SearchBounded(_ context.Context, _ string, depth, expand int) ([]enterprisegraph.SearchHit, error) {
	s.depth = depth
	s.expand = expand
	return s.hits, nil
}

type evidenceFilterStub struct {
	seenFilters raw.SearchFilters
}

func (f *evidenceFilterStub) FilterEvidence(_ context.Context, evidenceIDs []string, filters raw.SearchFilters) ([]string, error) {
	f.seenFilters = filters
	if filters.Company == "Allowed Co" {
		return evidenceIDs, nil
	}
	return nil, nil
}

func TestGraphLaneBoundsTraversalAndAppliesMetadataFilter(t *testing.T) {
	searcher := &graphSearcherStub{hits: []enterprisegraph.SearchHit{{
		RelationID: "r1", SourceName: "A", RelationType: "CONTROLS", TargetName: "B",
		EvidenceIDs: []string{"e1"}, Score: 0.9,
	}}}
	filter := &evidenceFilterStub{}
	lane := &GraphLane{Searcher: searcher, EvidenceFilter: filter, MaxDepth: 99, MaxExpand: 99}
	got, err := lane.Search(context.Background(), "A", raw.SearchFilters{Company: "Allowed Co"}, 4)
	if err != nil {
		t.Fatal(err)
	}
	if searcher.depth != 2 || searcher.expand != 4 {
		t.Fatalf("unexpected traversal bounds depth=%d expand=%d", searcher.depth, searcher.expand)
	}
	if len(got) != 1 || got[0].ID != "r1" || len(got[0].EvidenceIDs) != 1 {
		t.Fatalf("unexpected candidates: %#v", got)
	}
	if filter.seenFilters.Company != "Allowed Co" {
		t.Fatalf("metadata filter was not forwarded: %#v", filter.seenFilters)
	}
}

func TestGraphLaneRejectsFilteredOutEvidence(t *testing.T) {
	searcher := &graphSearcherStub{hits: []enterprisegraph.SearchHit{{RelationID: "r1", SourceName: "A", RelationType: "CONTROLS", TargetName: "B", EvidenceIDs: []string{"e1"}}}}
	lane := &GraphLane{Searcher: searcher, EvidenceFilter: &evidenceFilterStub{}}
	got, err := lane.Search(context.Background(), "A", raw.SearchFilters{Company: "Other Co"}, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 0 {
		t.Fatalf("expected filtered candidate to be removed: %#v", got)
	}
}
