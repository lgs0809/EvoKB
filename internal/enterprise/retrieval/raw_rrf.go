package retrieval

import (
	"sort"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type rawAccumulator struct {
	candidate raw.Candidate
	score float64
}

func addRawRanks(items map[string]*rawAccumulator, values []raw.Candidate) {
	for rank, candidate := range values {
		item := items[candidate.ChunkID]
		if item == nil {
			item = &rawAccumulator{candidate: candidate}
			items[candidate.ChunkID] = item
		}
		item.score += 1.0 / float64(61+rank)
	}
}

func fuseRaw(a, b []raw.Candidate, limit int) []raw.Candidate {
	items := make(map[string]*rawAccumulator)
	addRawRanks(items, a)
	addRawRanks(items, b)
	out := make([]raw.Candidate, 0, len(items))
	for _, item := range items {
		item.candidate.Score = item.score
		out = append(out, item.candidate)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	if limit > 0 && len(out) > limit { out = out[:limit] }
	return out
}
