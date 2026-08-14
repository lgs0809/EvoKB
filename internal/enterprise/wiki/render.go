package wiki

import (
	"fmt"
	"sort"
	"strings"
)

func RenderMarkdown(template Template, wiki StoredWiki) string {
	bySection := make(map[string][]Claim)
	for _, claim := range wiki.Claims {
		if claim.GroundingStatus == "PASS" && template.HasSection(claim.Section) {
			bySection[claim.Section] = append(bySection[claim.Section], claim)
		}
	}
	for section := range bySection {
		sort.SliceStable(bySection[section], func(i, j int) bool {
			return bySection[section][i].ClaimText < bySection[section][j].ClaimText
		})
	}
	var out strings.Builder
	fmt.Fprintf(&out, "# %s\n\n", strings.TrimSpace(wiki.Title))
	fmt.Fprintf(&out, "> wiki_type: %s\n\n", wiki.WikiType)
	for _, section := range template.Sections {
		claims := bySection[section.Name]
		if len(claims) == 0 {
			continue
		}
		fmt.Fprintf(&out, "## %s\n\n", section.Name)
		for _, claim := range claims {
			fmt.Fprintf(&out, "- %s\n", strings.TrimSpace(claim.ClaimText))
		}
		out.WriteString("\n")
	}
	return out.String()
}
