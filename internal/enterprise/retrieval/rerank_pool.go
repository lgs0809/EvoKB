package retrieval

func balancedRerankPool(candidates []Candidate, limit int) []Candidate {
	if limit <= 0 || len(candidates) <= limit {
		return candidates
	}
	order := []string{TypeRaw, TypeSummary, TypeWiki, TypeGraph}
	groups := make(map[string][]Candidate, len(order))
	for _, candidate := range candidates {
		groups[candidate.ResultType] = append(groups[candidate.ResultType], candidate)
	}
	out := make([]Candidate, 0, limit)
	for rank := 0; len(out) < limit; rank++ {
		added := false
		for _, kind := range order {
			group := groups[kind]
			if rank >= len(group) {
				continue
			}
			out = append(out, group[rank])
			added = true
			if len(out) == limit {
				break
			}
		}
		if !added {
			break
		}
	}
	return out
}
