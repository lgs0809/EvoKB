package raw

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
)

type VersionContext struct {
	DocumentID string
	VersionID  string
	Metadata   Metadata
}

func (s *MySQLStore) GetVersionContext(ctx context.Context, versionID string) (VersionContext, error) {
	var out VersionContext
	var tags []byte
	var documentDate sql.NullTime
	var sourceDepartment, materialCategory, company, autoType, manualType sql.NullString
	err := s.db.QueryRowContext(ctx, `
SELECT v.document_id,v.version_id,m.source_department,m.material_category,m.manual_tags,m.company,m.document_date,m.auto_document_type,m.manual_document_type_override
FROM document_version v
LEFT JOIN document_metadata m ON m.version_id=v.version_id
WHERE v.version_id=?`, versionID).Scan(
		&out.DocumentID, &out.VersionID, &sourceDepartment, &materialCategory, &tags, &company, &documentDate, &autoType, &manualType,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return VersionContext{}, fmt.Errorf("version %s not found", versionID)
	}
	if err != nil {
		return VersionContext{}, fmt.Errorf("mysql version context: %w", err)
	}
	out.Metadata.SourceDepartment = sourceDepartment.String
	out.Metadata.MaterialCategory = materialCategory.String
	out.Metadata.Company = company.String
	out.Metadata.DocumentType = autoType.String
	if manualType.Valid && manualType.String != "" {
		out.Metadata.DocumentType = manualType.String
	}
	if len(tags) > 0 {
		_ = json.Unmarshal(tags, &out.Metadata.ManualTags)
	}
	if documentDate.Valid {
		value := documentDate.Time.UTC()
		out.Metadata.DocumentDate = &value
	}
	return out, nil
}

