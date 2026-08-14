package summary

import (
	"fmt"
	"sort"
	"strings"
)

// RenderMarkdown is intentionally deterministic. The model produces structured
// facts only; section ordering, evidence markers and Markdown are owned by code.
func RenderMarkdown(template Template, source UnitSource, facts []Fact) string {
	bySection := make(map[string][]Fact, len(template.Sections))
	for _, f := range facts {
		if f.GroundingStatus != "PASS" || !template.HasSection(f.Section) {
			continue
		}
		bySection[f.Section] = append(bySection[f.Section], f)
	}
	for section := range bySection {
		sort.SliceStable(bySection[section], func(i, j int) bool {
			if bySection[section][i].FactText == bySection[section][j].FactText {
				return bySection[section][i].ID < bySection[section][j].ID
			}
			return bySection[section][i].FactText < bySection[section][j].FactText
		})
	}

	var b strings.Builder
	title := strings.TrimSpace(source.Unit.Title)
	if title == "" {
		title = source.Unit.DocumentType
	}
	fmt.Fprintf(&b, "# %s\n\n", title)
	fmt.Fprintf(&b, "> document_type: %s  \n", source.Unit.DocumentType)
	fmt.Fprintf(&b, "> version_id: %s  \n", source.Unit.VersionID)
	fmt.Fprintf(&b, "> unit_id: %s\n\n", source.Unit.ID)
	for _, section := range template.Sections {
		items := bySection[section.Name]
		if len(items) == 0 {
			continue
		}
		fmt.Fprintf(&b, "## %s\n\n", section.Name)
		for _, f := range items {
			fmt.Fprintf(&b, "- %s", strings.TrimSpace(f.FactText))
			if len(f.EvidenceIDs) > 0 {
				fmt.Fprintf(&b, "  <!-- evidence:%s -->", strings.Join(f.EvidenceIDs, ","))
			}
			b.WriteString("\n")
		}
		b.WriteString("\n")
	}
	return b.String()
}
