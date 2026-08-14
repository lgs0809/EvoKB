package raw

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type Engine struct {
	Objects       ObjectStore
	Metadata      MetadataStore
	Parser        Parser
	Search        SearchStore
	Embedder      Embedder
	Reranker      Reranker
	DerivedSearch DerivedSearchLifecycle
}

type IngestRequest struct {
	Path        string
	LogicalName string
	SourcePath  string
	Metadata    Metadata
	Progress    func(stage string)
}

type IngestResult struct {
	DocumentID          string
	VersionID           string
	VersionNo           uint64
	Reused              bool
	Retried             bool
	BlockCount          int
	ChunkCount          int
	EvidenceCount       int
	OriginalObjectKey   string
	MarkdownObjectKey   string
	StructuredObjectKey string
	Warnings            []string
}

func (e *Engine) IngestPath(ctx context.Context, req IngestRequest) (*IngestResult, error) {
	if e.Objects == nil || e.Metadata == nil || e.Parser == nil || e.Search == nil || e.Embedder == nil {
		return nil, fmt.Errorf("raw ingest: engine adapters are incomplete")
	}
	if strings.TrimSpace(req.Path) == "" {
		return nil, fmt.Errorf("raw ingest: path is required")
	}
	progress := func(stage string) {
		if req.Progress != nil {
			req.Progress(stage)
		}
	}
	filename := filepath.Base(req.Path)
	logicalName := strings.TrimSpace(req.LogicalName)
	if logicalName == "" {
		logicalName = filename
	}
	sha, size, err := hashFile(req.Path)
	if err != nil {
		return nil, err
	}
	doc, err := e.Metadata.FindDocumentByLogicalName(ctx, logicalName)
	if err != nil {
		return nil, err
	}
	var retryVersion *DocumentVersion
	if doc == nil {
		doc = &Document{ID: newID(), LogicalName: logicalName, SourcePath: req.SourcePath}
	} else {
		existing, err := e.Metadata.FindVersionBySHA(ctx, doc.ID, sha)
		if err != nil {
			return nil, err
		}
		if existing != nil {
			switch existing.Lifecycle {
			case "ACTIVE":
				return &IngestResult{
					DocumentID: doc.ID, VersionID: existing.ID, VersionNo: existing.VersionNo, Reused: true,
					OriginalObjectKey: existing.OriginalObjectKey, MarkdownObjectKey: existing.MarkdownObjectKey,
					StructuredObjectKey: existing.StructuredKey,
				}, nil
			case "FAILED":
				retryVersion = existing
			default:
				return nil, fmt.Errorf("raw ingest: identical content already exists as version %s with lifecycle %s", existing.ID, existing.Lifecycle)
			}
		}
	}
	previousActive, err := e.Metadata.CurrentActiveVersion(ctx, doc.ID)
	if err != nil {
		return nil, err
	}

	mediaType := mime.TypeByExtension(strings.ToLower(filepath.Ext(filename)))
	if mediaType == "" {
		mediaType = "application/octet-stream"
	}
	var version DocumentVersion
	if retryVersion != nil {
		version = *retryVersion
		version.Lifecycle = "PENDING"
		version.OriginalFilename = filename
		version.MediaType = mediaType
		version.SizeBytes = size
		version.SHA256 = sha
	} else {
		versionNo, err := e.Metadata.NextVersionNo(ctx, doc.ID)
		if err != nil {
			return nil, err
		}
		version = DocumentVersion{
			ID: versionIDOrNew(""), DocumentID: doc.ID, VersionNo: versionNo, Lifecycle: "PENDING",
			OriginalFilename: filename, MediaType: mediaType, SizeBytes: size, SHA256: sha,
		}
	}
	versionID := version.ID
	originalKey := version.OriginalObjectKey
	if originalKey == "" {
		originalKey = fmt.Sprintf("original/%s/%s/%s", doc.ID, versionID, filename)
		version.OriginalObjectKey = originalKey
	}
	markdownKey := fmt.Sprintf("parsed/%s/%s/markdown.md", doc.ID, versionID)
	structuredKey := fmt.Sprintf("parsed/%s/%s/structured.json", doc.ID, versionID)

	progress("STORING")
	f, err := os.Open(req.Path)
	if err != nil {
		return nil, fmt.Errorf("raw ingest open: %w", err)
	}
	if err := e.Objects.Put(ctx, originalKey, f, size, mediaType); err != nil {
		_ = f.Close()
		return nil, err
	}
	_ = f.Close()

	if retryVersion != nil {
		if err := e.Search.EnsureRawIndex(ctx, e.Embedder.Dimensions()); err != nil {
			return nil, fmt.Errorf("raw ingest retry search index: %w", err)
		}
		if err := e.Search.DeleteVersion(ctx, versionID); err != nil {
			return nil, fmt.Errorf("raw ingest retry clear search: %w", err)
		}
		if err := e.Metadata.ResetFailedVersion(ctx, versionID, req.Metadata); err != nil {
			return nil, err
		}
	} else if err := e.Metadata.CreatePendingVersion(ctx, *doc, version, req.Metadata); err != nil {
		return nil, err
	}

	fail := func(code string, cause error) (*IngestResult, error) {
		_ = e.Search.DeactivateVersion(ctx, versionID)
		_ = e.Metadata.MarkVersionFailed(ctx, versionID, code, cause.Error())
		return nil, cause
	}

	progress("PARSING")
	original, err := e.Objects.Get(ctx, originalKey)
	if err != nil {
		return fail("OBJECT_READ_FAILED", err)
	}
	parsed, err := e.Parser.Parse(ctx, filename, original)
	_ = original.Close()
	if err != nil {
		return fail("PARSER_FAILED", err)
	}
	if parsed.ParserName == "" || parsed.ParserVersion == "" {
		return fail("PARSER_IDENTITY_MISSING", fmt.Errorf("raw ingest: parser name/version missing"))
	}
	if err := e.Objects.Put(ctx, markdownKey, strings.NewReader(parsed.Markdown), int64(len([]byte(parsed.Markdown))), "text/markdown; charset=utf-8"); err != nil {
		return fail("PARSED_MARKDOWN_STORE_FAILED", err)
	}
	structuredBytes, err := buildStructuredArtifact(parsed)
	if err != nil {
		return fail("PARSED_STRUCTURED_BUILD_FAILED", err)
	}
	if err := e.Objects.Put(ctx, structuredKey, bytes.NewReader(structuredBytes), int64(len(structuredBytes)), "application/json"); err != nil {
		return fail("PARSED_STRUCTURED_STORE_FAILED", err)
	}

	progress("EVIDENCE")
	blocks := BuildRawBlocks(versionID, parsed.Blocks)
	evidence := buildEvidence(doc.ID, versionID, originalKey, blocks)
	if err := e.Metadata.SaveParsed(ctx, versionID, markdownKey, structuredKey, parsed.ParserName, parsed.ParserVersion, blocks, evidence); err != nil {
		return fail("RAW_FACT_STORE_FAILED", err)
	}
	progress("EMBEDDING")
	chunks := SmartChunks(doc.ID, versionID, req.Metadata, blocks)
	texts := make([]string, len(chunks))
	for i := range chunks {
		texts[i] = chunks[i].Content
	}
	vectors, err := e.Embedder.Embed(ctx, texts)
	if err != nil {
		return fail("EMBEDDING_FAILED", err)
	}
	if len(vectors) != len(chunks) {
		return fail("EMBEDDING_COUNT_MISMATCH", fmt.Errorf("embedding count %d != chunks %d", len(vectors), len(chunks)))
	}
	for i := range chunks {
		chunks[i].Embedding = vectors[i]
	}
	progress("INDEXING")
	if err := e.Search.EnsureRawIndex(ctx, e.Embedder.Dimensions()); err != nil {
		return fail("SEARCH_INDEX_INIT_FAILED", err)
	}
	evidenceByBlock := make(map[string]string, len(evidence))
	for _, ev := range evidence {
		evidenceByBlock[ev.BlockID] = ev.ID
	}
	evidenceByChunk := make(map[string][]string, len(chunks))
	for _, ch := range chunks {
		seen := map[string]struct{}{}
		for _, blockID := range ch.SourceBlockIDs {
			if id := evidenceByBlock[blockID]; id != "" {
				if _, ok := seen[id]; !ok {
					evidenceByChunk[ch.ID] = append(evidenceByChunk[ch.ID], id)
					seen[id] = struct{}{}
				}
			}
		}
	}
	if err := e.Search.IndexRaw(ctx, chunks, evidenceByChunk); err != nil {
		return fail("SEARCH_INDEX_FAILED", err)
	}
	// Search is switched first. Until MySQL activation succeeds, the new Raw
	// candidate cannot resolve to active Evidence, so it cannot escape the final
	// Evidence gate. This ordering keeps a search activation failure from first
	// staling valid Summary/Wiki/Graph support in the durable fact layer.
	progress("ACTIVATING")
	if err := e.Search.ActivateVersion(ctx, doc.ID, versionID); err != nil {
		_ = e.Search.DeactivateVersion(ctx, versionID)
		return fail("SEARCH_ACTIVATE_FAILED", err)
	}
	if err := e.Metadata.ActivateVersion(ctx, doc.ID, versionID); err != nil {
		_ = e.Search.DeactivateVersion(ctx, versionID)
		if previousActive != nil {
			_ = e.Search.ActivateVersion(ctx, doc.ID, previousActive.ID)
		}
		return fail("METADATA_ACTIVATE_FAILED", err)
	}
	var warnings []string
	if previousActive != nil && e.DerivedSearch != nil {
		if err := e.DerivedSearch.DeactivateVersion(ctx, previousActive.ID); err != nil {
			warnings = append(warnings, "derived search cleanup failed: "+err.Error())
		}
	}
	return &IngestResult{
		DocumentID: doc.ID, VersionID: versionID, VersionNo: version.VersionNo, Retried: retryVersion != nil,
		BlockCount: len(blocks), ChunkCount: len(chunks), EvidenceCount: len(evidence),
		OriginalObjectKey: originalKey, MarkdownObjectKey: markdownKey, StructuredObjectKey: structuredKey,
		Warnings: warnings,
	}, nil
}

func versionIDOrNew(id string) string {
	if strings.TrimSpace(id) != "" {
		return id
	}
	return newID()
}

type ResolvedCandidate struct {
	Candidate Candidate
	Evidence  []Evidence
}

type ResolvedSearchResult struct {
	SearchID string
	Results  []ResolvedCandidate
}

// SearchRaw performs BM25 and vector retrieval in parallel logical lanes, RRF
// fusion, one and only one rerank, then Evidence Resolver back to active Raw.
func (e *Engine) SearchRaw(ctx context.Context, query string, filters SearchFilters, candidateQuota, finalLimit int) (*ResolvedSearchResult, error) {
	if e.Search == nil || e.Embedder == nil || e.Reranker == nil || e.Metadata == nil {
		return nil, fmt.Errorf("raw search: engine adapters are incomplete")
	}
	if candidateQuota <= 0 {
		candidateQuota = 30
	}
	if finalLimit <= 0 {
		finalLimit = 10
	}
	qv, err := e.Embedder.Embed(ctx, []string{query})
	if err != nil {
		return nil, err
	}
	if len(qv) != 1 {
		return nil, fmt.Errorf("raw search: expected one query embedding")
	}
	bm25, err := e.Search.SearchBM25(ctx, query, filters, candidateQuota)
	if err != nil {
		return nil, err
	}
	vec, err := e.Search.SearchVector(ctx, qv[0], filters, candidateQuota)
	if err != nil {
		return nil, err
	}
	fused := rrfFuse(bm25, vec, 60)
	if len(fused) == 0 {
		return &ResolvedSearchResult{SearchID: newID()}, nil
	}
	docs := make([]string, len(fused))
	for i := range fused {
		docs[i] = fused[i].Content
	}
	scores, err := e.Reranker.Rerank(ctx, query, docs)
	if err != nil {
		return nil, err
	}
	if len(scores) != len(fused) {
		return nil, fmt.Errorf("raw search: rerank score count mismatch")
	}
	for i := range fused {
		fused[i].Score = scores[i]
	}
	sort.SliceStable(fused, func(i, j int) bool { return fused[i].Score > fused[j].Score })
	if len(fused) > finalLimit {
		fused = fused[:finalLimit]
	}
	result := &ResolvedSearchResult{SearchID: newID()}
	for _, c := range fused {
		evs, err := e.Metadata.ResolveEvidence(ctx, c.EvidenceIDs)
		if err != nil {
			return nil, err
		}
		// A high-level candidate without resolvable active Raw evidence is never
		// returned as an answerable result. This rule applies to Raw now and to
		// Summary/Wiki/Graph candidates when they join the same resolver later.
		if len(evs) == 0 {
			continue
		}
		result.Results = append(result.Results, ResolvedCandidate{Candidate: c, Evidence: evs})
	}
	return result, nil
}

func hashFile(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

func buildStructuredArtifact(p *ParsedDocument) ([]byte, error) {
	var middle, list any
	if err := json.Unmarshal([]byte(p.MiddleJSON), &middle); err != nil {
		return nil, err
	}
	if err := json.Unmarshal([]byte(p.ContentList), &list); err != nil {
		return nil, err
	}
	return json.Marshal(map[string]any{
		"parser_name":    p.ParserName,
		"parser_version": p.ParserVersion,
		"middle_json":    middle,
		"content_list":   list,
	})
}

func buildEvidence(documentID, versionID, originalKey string, blocks []RawBlock) []Evidence {
	out := make([]Evidence, 0, len(blocks))
	for _, b := range blocks {
		loc := map[string]any{
			"block_id":     b.ID,
			"block_no":     b.BlockNo,
			"block_type":   b.BlockType,
			"heading_path": b.HeadingPath,
			"page_start":   b.PageStart,
			"page_end":     b.PageEnd,
			"bbox":         b.BBox,
			"page_size":    b.PageSize,
		}
		id := deterministicID("evidence:" + versionID + ":" + b.ID)
		out = append(out, Evidence{
			ID: id, DocumentID: documentID, VersionID: versionID, BlockID: b.ID,
			ObjectKey: originalKey, Location: loc, Content: b.Content, Hash: b.ContentHash,
		})
	}
	return out
}

// rrfFuse keeps source retrieval scores separate: BM25 and vector scores are
// not numerically comparable, so rank fusion precedes the single cross-encoder
// rerank.
func rrfFuse(a, b []Candidate, k float64) []Candidate {
	type acc struct {
		c Candidate
		s float64
	}
	m := map[string]*acc{}
	add := func(list []Candidate) {
		for i, c := range list {
			x := m[c.ChunkID]
			if x == nil {
				copyC := c
				x = &acc{c: copyC}
				m[c.ChunkID] = x
			}
			x.s += 1.0 / (k + float64(i+1))
		}
	}
	add(a)
	add(b)
	out := make([]Candidate, 0, len(m))
	for _, x := range m {
		x.c.Score = x.s
		out = append(out, x.c)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out
}
