package raw

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	DefaultTargetTokens   = 900
	DefaultOverlapTokens  = 135 // 15% of 900
	DefaultBoundaryWindow = 200
)

func newID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(fmt.Sprintf("crypto/rand: %v", err))
	}
	return uuidBytes(b)
}

func deterministicID(seed string) string {
	h := sha256.Sum256([]byte(seed))
	var b [16]byte
	copy(b[:], h[:16])
	return uuidBytes(b)
}

func uuidBytes(b [16]byte) string {
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func contentHash(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func normalizeText(s string) string {
	return strings.Join(strings.Fields(strings.TrimSpace(s)), " ")
}

// estimateTokens is intentionally deterministic and language-aware enough for
// chunk sizing without coupling ingest to a particular model tokenizer. CJK
// runes count approximately one token; Latin/digit runs count approximately
// one token per four bytes. Exact embedding tokenization remains the model
// provider's responsibility.
func estimateTokens(s string) int {
	cjk := 0
	latinBytes := 0
	other := 0
	for _, r := range s {
		switch {
		case unicode.In(r, unicode.Han, unicode.Hangul, unicode.Hiragana, unicode.Katakana):
			cjk++
		case r <= unicode.MaxASCII && (unicode.IsLetter(r) || unicode.IsDigit(r)):
			latinBytes++
		case !unicode.IsSpace(r):
			other++
		}
	}
	t := cjk + (latinBytes+3)/4 + (other+2)/3
	if t == 0 && utf8.RuneCountInString(s) > 0 {
		return 1
	}
	return t
}

func BuildRawBlocks(versionID string, parsed []ParsedBlock) []RawBlock {
	blocks := make([]RawBlock, 0, len(parsed))
	headings := make([]string, 0, 6)
	for i, p := range parsed {
		text := blockText(p)
		if p.Type == "text" && p.TextLevel > 0 && strings.TrimSpace(p.Text) != "" {
			level := p.TextLevel
			if level < 1 {
				level = 1
			}
			if level > len(headings)+1 {
				level = len(headings) + 1
			}
			if level <= len(headings) {
				headings = headings[:level-1]
			}
			headings = append(headings, normalizeText(p.Text))
		}
		norm := normalizeText(text)
		page := p.Page + 1
		idSeed := fmt.Sprintf("%s:%d:%s:%s", versionID, i, p.Type, norm)
		id := "rb_" + contentHash(idSeed)[:40]
		var ps, pe *int
		if p.Page >= 0 {
			v := page
			ps, pe = &v, &v
		}
		blocks = append(blocks, RawBlock{
			ID: id, VersionID: versionID, BlockNo: i, BlockType: p.Type,
			Content: text, Normalized: norm, HeadingPath: append([]string(nil), headings...),
			PageStart: ps, PageEnd: pe, BBox: append([]float64(nil), p.BBox...), PageSize: append([]float64(nil), p.PageSize...),
			SourceBlockIDs: []string{id}, ContentHash: contentHash(text), TokenCount: estimateTokens(text),
		})
	}
	return blocks
}

func blockText(p ParsedBlock) string {
	switch p.Type {
	case "text", "equation", "interline_equation":
		return strings.TrimSpace(p.Text)
	case "table":
		if strings.TrimSpace(p.TableBody) != "" {
			return strings.TrimSpace(p.TableBody)
		}
		return strings.TrimSpace(p.TableHTML)
	case "image":
		if strings.TrimSpace(p.Text) != "" {
			return strings.TrimSpace(p.Text)
		}
		if p.ImagePath != "" {
			return "[image] " + p.ImagePath
		}
	}
	return strings.TrimSpace(p.Text)
}

type chunkPart struct {
	block RawBlock
}

func SmartChunks(documentID, versionID string, md Metadata, blocks []RawBlock) []Chunk {
	return SmartChunksWithLimits(documentID, versionID, md, blocks, DefaultTargetTokens, DefaultOverlapTokens, DefaultBoundaryWindow)
}

func SmartChunksWithLimits(documentID, versionID string, md Metadata, blocks []RawBlock, target, overlap, boundary int) []Chunk {
	if target <= 0 {
		target = DefaultTargetTokens
	}
	if overlap < 0 {
		overlap = 0
	}
	if boundary < 0 {
		boundary = 0
	}
	var out []Chunk
	var cur []chunkPart
	curTokens := 0

	flush := func() {
		if len(cur) == 0 {
			return
		}
		out = append(out, makeChunk(documentID, versionID, md, cur))
		if overlap == 0 {
			cur, curTokens = nil, 0
			return
		}
		carry := make([]chunkPart, 0, len(cur))
		tokens := 0
		for i := len(cur) - 1; i >= 0; i-- {
			b := cur[i].block
			if b.BlockType == "table" && len(carry) > 0 {
				break
			}
			if tokens+b.TokenCount > overlap && len(carry) > 0 {
				break
			}
			carry = append(carry, cur[i])
			tokens += b.TokenCount
		}
		for i, j := 0, len(carry)-1; i < j; i, j = i+1, j-1 {
			carry[i], carry[j] = carry[j], carry[i]
		}
		cur, curTokens = carry, tokens
	}

	for _, b := range blocks {
		if strings.TrimSpace(b.Content) == "" {
			continue
		}
		isHeading := b.BlockType == "text" && len(b.HeadingPath) > 0 && b.Normalized == b.HeadingPath[len(b.HeadingPath)-1]
		if isHeading && curTokens >= target-boundary && len(cur) > 0 {
			flush()
		}
		// Tables are atomic regions. Flush before a table that would overrun the
		// target; an oversized table remains a single chunk rather than being cut.
		if b.BlockType == "table" && len(cur) > 0 && curTokens+b.TokenCount > target {
			flush()
		}
		if b.BlockType != "table" && len(cur) > 0 && curTokens+b.TokenCount > target+boundary {
			flush()
		}
		cur = append(cur, chunkPart{block: b})
		curTokens += b.TokenCount
		if b.BlockType == "table" && curTokens >= target {
			flush()
		}
	}
	flush()
	return out
}

func makeChunk(documentID, versionID string, md Metadata, parts []chunkPart) Chunk {
	contents := make([]string, 0, len(parts))
	sourceIDs := make([]string, 0, len(parts))
	var pageStart, pageEnd *int
	var heading []string
	tokens := 0
	for _, p := range parts {
		b := p.block
		contents = append(contents, b.Content)
		sourceIDs = append(sourceIDs, b.ID)
		tokens += b.TokenCount
		if len(b.HeadingPath) > 0 {
			heading = append([]string(nil), b.HeadingPath...)
		}
		if b.PageStart != nil && (pageStart == nil || *b.PageStart < *pageStart) {
			v := *b.PageStart
			pageStart = &v
		}
		if b.PageEnd != nil && (pageEnd == nil || *b.PageEnd > *pageEnd) {
			v := *b.PageEnd
			pageEnd = &v
		}
	}
	content := strings.Join(contents, "\n\n")
	seed := versionID + ":" + strings.Join(sourceIDs, ",") + ":" + contentHash(content)
	return Chunk{
		ID: "ch_" + contentHash(seed)[:40], DocumentID: documentID, VersionID: versionID,
		Content: content, Normalized: normalizeText(content), HeadingPath: heading,
		SourceBlockIDs: sourceIDs, PageStart: pageStart, PageEnd: pageEnd,
		ContentHash: contentHash(content), TokenCount: tokens, Metadata: md,
	}
}
