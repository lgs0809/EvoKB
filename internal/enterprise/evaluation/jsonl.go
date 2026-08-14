package evaluation

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

func LoadGoldenJSONL(path string) ([]ArtifactGoldenCase, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var out []ArtifactGoldenCase
	seenIDs := map[string]struct{}{}
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), 4*1024*1024)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		var item ArtifactGoldenCase
		if err := json.Unmarshal([]byte(line), &item); err != nil {
			return nil, fmt.Errorf("artifact golden line %d: %w", lineNo, err)
		}
		if err := validateGoldenCase(item); err != nil {
			return nil, fmt.Errorf("artifact golden line %d: %w", lineNo, err)
		}
		if _, ok := seenIDs[item.ID]; ok {
			return nil, fmt.Errorf("artifact golden line %d: duplicate id %q", lineNo, item.ID)
		}
		seenIDs[item.ID] = struct{}{}
		out = append(out, item)
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func validateGoldenCase(item ArtifactGoldenCase) error {
	if strings.TrimSpace(item.ID) == "" {
		return fmt.Errorf("id is required")
	}
	if len(item.VersionIDs) == 0 {
		return fmt.Errorf("version_ids is required")
	}
	for _, versionID := range item.VersionIDs {
		if strings.TrimSpace(versionID) == "" {
			return fmt.Errorf("version_ids contains an empty value")
		}
	}
	for i, expected := range item.ExpectedSummaryFacts {
		if strings.TrimSpace(expected.Text) == "" {
			return fmt.Errorf("expected_summary_facts[%d].text is required", i)
		}
	}
	for i, expected := range item.ExpectedRelations {
		if strings.TrimSpace(expected.Source) == "" || strings.TrimSpace(expected.RelationType) == "" || strings.TrimSpace(expected.Target) == "" {
			return fmt.Errorf("expected_relations[%d] requires source, relation_type, and target", i)
		}
	}
	for i, expected := range item.ExpectedEntityMappings {
		if strings.TrimSpace(expected.SurfaceForm) == "" || strings.TrimSpace(expected.EntityType) == "" || strings.TrimSpace(expected.CanonicalName) == "" {
			return fmt.Errorf("expected_entity_mappings[%d] requires surface_form, entity_type, and canonical_name", i)
		}
	}
	for i, expected := range item.ExpectedWikiClaims {
		if strings.TrimSpace(expected.Text) == "" {
			return fmt.Errorf("expected_wiki_claims[%d].text is required", i)
		}
	}
	return nil
}
