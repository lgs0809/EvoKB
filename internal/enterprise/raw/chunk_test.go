package raw

import "testing"

func TestSmartChunksKeepsTableAtomicAndCarriesHeading(t *testing.T) {
	blocks := []RawBlock{
		{ID: "h1", BlockNo: 0, BlockType: "text", Content: "Chapter One", Normalized: "Chapter One", HeadingPath: []string{"Chapter One"}, TokenCount: 3},
		{ID: "p1", BlockNo: 1, BlockType: "text", Content: "first paragraph", Normalized: "first paragraph", HeadingPath: []string{"Chapter One"}, TokenCount: 4},
		{ID: "tb", BlockNo: 2, BlockType: "table", Content: "A|B\n1|2", Normalized: "A|B 1|2", HeadingPath: []string{"Chapter One"}, TokenCount: 20},
		{ID: "p2", BlockNo: 3, BlockType: "text", Content: "second paragraph", Normalized: "second paragraph", HeadingPath: []string{"Chapter One"}, TokenCount: 4},
	}
	chunks := SmartChunksWithLimits("doc", "ver", Metadata{}, blocks, 10, 0, 0)
	if len(chunks) != 3 {
		t.Fatalf("expected 3 chunks, got %d: %#v", len(chunks), chunks)
	}
	if len(chunks[1].SourceBlockIDs) != 1 || chunks[1].SourceBlockIDs[0] != "tb" {
		t.Fatalf("table must remain atomic, got %#v", chunks[1].SourceBlockIDs)
	}
	if got := chunks[1].HeadingPath; len(got) != 1 || got[0] != "Chapter One" {
		t.Fatalf("heading path lost: %#v", got)
	}
}

func TestBuildRawBlocksUsesStableSourceLocation(t *testing.T) {
	parsed := []ParsedBlock{
		{Type: "text", Text: "Heading", TextLevel: 1, Page: 0, BBox: []float64{1, 2, 3, 4}},
		{Type: "text", Text: "Content", Page: 1, BBox: []float64{5, 6, 7, 8}},
	}
	blocks := BuildRawBlocks("ver-1", parsed)
	if len(blocks) != 2 {
		t.Fatalf("expected 2 blocks, got %d", len(blocks))
	}
	if blocks[0].ID == "" || blocks[0].ID == blocks[1].ID {
		t.Fatalf("block IDs not stable/unique: %#v", blocks)
	}
	if blocks[0].PageStart == nil || *blocks[0].PageStart != 1 || blocks[1].PageStart == nil || *blocks[1].PageStart != 2 {
		t.Fatalf("page conversion incorrect")
	}
	if len(blocks[1].HeadingPath) != 1 || blocks[1].HeadingPath[0] != "Heading" {
		t.Fatalf("expected inherited heading, got %#v", blocks[1].HeadingPath)
	}
}

func TestRRFFusionDeduplicatesCandidates(t *testing.T) {
	a := []Candidate{{ChunkID: "a"}, {ChunkID: "b"}}
	b := []Candidate{{ChunkID: "b"}, {ChunkID: "c"}}
	out := rrfFuse(a, b, 60)
	if len(out) != 3 {
		t.Fatalf("expected 3 unique results, got %d", len(out))
	}
	if out[0].ChunkID != "b" {
		t.Fatalf("candidate appearing in both lanes must rank first, got %#v", out)
	}
}
