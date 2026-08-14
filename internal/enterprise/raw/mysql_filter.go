package raw

import (
	"context"
	"fmt"
)

// FilterEvidence returns the subset of active evidence IDs whose owning
// Document Version satisfies the explicit query-time metadata filters. The
// returned order follows the input order so higher-level retrieval lanes keep
// their deterministic evidence ordering.
func (s *MySQLStore) FilterEvidence(ctx context.Context, evidenceIDs []string, filters SearchFilters) ([]string, error) {
	if len(evidenceIDs) == 0 {
		return nil, nil
	}
	query := `SELECT e.evidence_id
FROM evidence e
JOIN document_version v ON v.version_id=e.version_id
LEFT JOIN document_metadata m ON m.version_id=e.version_id
WHERE e.active=TRUE AND v.lifecycle='ACTIVE' AND e.evidence_id IN (` + placeholders(len(evidenceIDs)) + `)`
	args := make([]any, 0, len(evidenceIDs)+8)
	for _, id := range evidenceIDs {
		args = append(args, id)
	}
	if filters.SourceDepartment != "" {
		query += ` AND m.source_department=?`
		args = append(args, filters.SourceDepartment)
	}
	if filters.MaterialCategory != "" {
		query += ` AND m.material_category=?`
		args = append(args, filters.MaterialCategory)
	}
	if filters.Company != "" {
		query += ` AND m.company=?`
		args = append(args, filters.Company)
	}
	if filters.DocumentType != "" {
		query += ` AND COALESCE(m.manual_document_type_override,m.auto_document_type)=?`
		args = append(args, filters.DocumentType)
	}
	if len(filters.VersionIDs) > 0 {
		query += ` AND e.version_id IN (` + placeholders(len(filters.VersionIDs)) + `)`
		for _, id := range filters.VersionIDs {
			args = append(args, id)
		}
	}
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("mysql filter evidence: %w", err)
	}
	defer rows.Close()
	allowed := make(map[string]struct{}, len(evidenceIDs))
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, fmt.Errorf("mysql filter evidence scan: %w", err)
		}
		allowed[id] = struct{}{}
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("mysql filter evidence rows: %w", err)
	}
	out := make([]string, 0, len(allowed))
	for _, id := range evidenceIDs {
		if _, ok := allowed[id]; ok {
			out = append(out, id)
		}
	}
	return out, nil
}
