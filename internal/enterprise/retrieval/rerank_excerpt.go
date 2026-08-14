package retrieval

import "strings"

func boundedRerankText(text, query string, maxRunes int) string {
	text = strings.TrimSpace(text)
	if maxRunes <= 0 {
		return text
	}
	runes := []rune(text)
	if len(runes) <= maxRunes {
		return text
	}
	needle := strings.TrimSpace(query)
	if needle == "" {
		return string(runes[:maxRunes])
	}
	lowerText := strings.ToLower(text)
	lowerNeedle := strings.ToLower(needle)
	byteIndex := strings.Index(lowerText, lowerNeedle)
	if byteIndex < 0 {
		for _, token := range queryTokens(needle) {
			if index := strings.Index(lowerText, strings.ToLower(token)); index >= 0 {
				byteIndex = index
				break
			}
		}
	}
	if byteIndex < 0 {
		return string(runes[:maxRunes])
	}
	center := len([]rune(text[:byteIndex]))
	start := center - maxRunes/3
	if start < 0 {
		start = 0
	}
	end := start + maxRunes
	if end > len(runes) {
		end = len(runes)
		start = end - maxRunes
		if start < 0 {
			start = 0
		}
	}
	return string(runes[start:end])
}

func queryTokens(query string) []string {
	fields := strings.FieldsFunc(query, func(r rune) bool {
		switch r {
		case ' ', '\t', '\r', '\n', ',', '.', ';', ':', '，', '。', '；', '：', '/', '\\', '|', '(', ')', '（', '）':
			return true
		default:
			return false
		}
	})
	// Prefer longer tokens because they are more discriminative for centering.
	for i := 0; i < len(fields); i++ {
		for j := i + 1; j < len(fields); j++ {
			if len([]rune(fields[j])) > len([]rune(fields[i])) {
				fields[i], fields[j] = fields[j], fields[i]
			}
		}
	}
	return fields
}
