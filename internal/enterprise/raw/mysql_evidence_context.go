package raw

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
)

type EvidenceContext struct {
	EvidenceID string
	DocumentID string
	VersionID  string
	Metadata   Metadata
}

func (s *MySQLStore) GetEvidenceContexts(ctx context.Context, evidenceIDs []string) ([]EvidenceContext, error) {
	if len(evidenceIDs) == 0 {
		return nil, nil
	}
	query := `SELECT e.evidence_id,e.document_id,e.version_id,m.source_department,m.material_category,m.manual_tags,m.company,m.document_date,m.auto_document_type,m.manual_document_type_override
FROM evidence e
JOIN document_version v ON v.version_id=e.version_id
LEFT JOIN document_metadata m ON m.version_id=e.version_id
WHERE e.active=TRUE AND v.lifecycle='ACTIVE' AND e.evidence_id IN (` + placeholders(len(evidenceIDs)) + `)`
	args := make([]any, len(evidenceIDs))
	for i, id := range evidenceIDs {
		args[i] = id
	}
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("mysql evidence contexts: %w", err)
	}
	defer rows.Close()
	byID := make(map[string]EvidenceContext, len(evidenceIDs))
	for rows.Next() {
		var item EvidenceContext
		var sourceDepartment, materialCategory, company, autoType, manualType sql.NullString
		var documentDate sql.NullTime
		var tags []byte
		if err := rows.Scan(&item.EvidenceID, &item.DocumentID, &item.VersionID, &sourceDepartment, &materialCategory, &tags, &company, &documentDate, &autoType, &manualType); err != nil {
			return nil, fmt.Errorf("mysql evidence contexts scan: %w", err)
		}
		item.Metadata.SourceDepartment = sourceDepartment.String
		item.Metadata.MaterialCategory = materialCategory.String
		item.Metadata.Company = company.String
		item.Metadata.DocumentType = autoType.String
		if manualType.Valid && manualType.String != "" {
			item.Metadata.DocumentType = manualType.String
		}
		if len(tags) > 0 {
			_ = json.Unmarshal(tags, &item.Metadata.ManualTags)
		}
		if documentDate.Valid {
			value := documentDate.Time.UTC()
			item.Metadata.DocumentDate = &value
		}
		byID[item.EvidenceID] = item
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("mysql evidence contexts rows: %w", err)
	}
	out := make([]EvidenceContext, 0, len(byID))
	for _, id := range evidenceIDs {
		if item, ok := byID[id]; ok {
			out = append(out, item)
		}
	}
	return out, nil
}
