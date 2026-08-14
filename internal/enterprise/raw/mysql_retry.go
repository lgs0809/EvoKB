package raw

import (
	"context"
	"encoding/json"
	"fmt"
)

// ResetFailedVersion moves a transiently failed version back to PENDING so the
// exact same file hash can be retried without manufacturing a duplicate
// document version. Raw/evidence rows are replaced by SaveParsed on retry.
func (s *MySQLStore) ResetFailedVersion(ctx context.Context, versionID string, md Metadata) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	res, err := tx.ExecContext(ctx, `
UPDATE document_version
SET lifecycle='PENDING', failure_code=NULL, failure_message=NULL,
    markdown_object_key=NULL, structured_object_key=NULL,
    parser_name=NULL, parser_version=NULL, parsed_at=NULL, activated_at=NULL
WHERE version_id=? AND lifecycle='FAILED'`, versionID)
	if err != nil {
		return fmt.Errorf("mysql reset failed version: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("mysql reset failed version rows: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("mysql reset failed version: version %s is not FAILED", versionID)
	}

	if _, err := tx.ExecContext(ctx, `UPDATE evidence SET active=? WHERE version_id=?`, false, versionID); err != nil {
		return fmt.Errorf("mysql reset failed evidence: %w", err)
	}

	tags, _ := json.Marshal(md.ManualTags)
	var docDate any
	if md.DocumentDate != nil {
		docDate = md.DocumentDate.UTC().Format("2006-01-02")
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE document_metadata
SET source_department=?, material_category=?, manual_tags=?, company=?, document_date=?, auto_document_type=?
WHERE version_id=?`, nullable(md.SourceDepartment), nullable(md.MaterialCategory), string(tags), nullable(md.Company), docDate, nullable(md.DocumentType), versionID); err != nil {
		return fmt.Errorf("mysql reset failed metadata: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("mysql reset failed commit: %w", err)
	}
	return nil
}
