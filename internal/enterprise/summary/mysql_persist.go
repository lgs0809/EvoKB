package summary

import (
	"context"
	"encoding/json"
	"fmt"
)

func (s *MySQLStore) UpsertUnit(ctx context.Context, unit DocumentUnit) error {
	boundary, _ := json.Marshal(map[string]int{"start_block_no": unit.StartBlockNo, "end_block_no": unit.EndBlockNo})
	_, err := s.db.ExecContext(ctx, `
INSERT INTO document_unit(unit_id,version_id,unit_no,title,structure_type,document_type,boundary_json)
VALUES(?,?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE title=VALUES(title),structure_type=VALUES(structure_type),document_type=VALUES(document_type),boundary_json=VALUES(boundary_json)`,
		unit.ID, unit.VersionID, unit.UnitNo, nullableString(unit.Title), unit.StructureType, unit.DocumentType, string(boundary))
	if err != nil {
		return fmt.Errorf("summary upsert unit: %w", err)
	}
	return nil
}

func (s *MySQLStore) SaveSummary(ctx context.Context, summary StoredSummary) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if summary.Lifecycle == "ACTIVE" {
		if _, err := tx.ExecContext(ctx, `UPDATE summary SET lifecycle='STALE' WHERE unit_id=? AND summary_id<>? AND lifecycle='ACTIVE'`, summary.UnitID, summary.ID); err != nil {
			return fmt.Errorf("summary stale previous: %w", err)
		}
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model)
VALUES(?,?,?,?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE object_key=VALUES(object_key),content_hash=VALUES(content_hash),grounding_status=VALUES(grounding_status),coverage_status=VALUES(coverage_status),lifecycle=VALUES(lifecycle),model=VALUES(model)`,
		summary.ID, summary.UnitID, summary.VersionID, summary.ObjectKey, summary.ContentHash,
		summary.GroundingStatus, summary.CoverageStatus, summary.Lifecycle, summary.Model); err != nil {
		return fmt.Errorf("summary save header: %w", err)
	}
	for _, fact := range summary.Facts {
		if _, err := tx.ExecContext(ctx, `
INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status)
VALUES(?,?,?,?,?)
ON DUPLICATE KEY UPDATE section=VALUES(section),fact_text=VALUES(fact_text),grounding_status=VALUES(grounding_status)`,
			fact.ID, summary.ID, fact.Section, fact.FactText, fact.GroundingStatus); err != nil {
			return fmt.Errorf("summary save fact: %w", err)
		}
		for _, evidenceID := range uniqueStrings(fact.EvidenceIDs) {
			if _, err := tx.ExecContext(ctx, `INSERT IGNORE INTO summary_evidence(fact_id,evidence_id) VALUES(?,?)`, fact.ID, evidenceID); err != nil {
				return fmt.Errorf("summary save evidence link: %w", err)
			}
		}
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("summary save commit: %w", err)
	}
	return nil
}

func nullableString(v string) any {
	if v == "" {
		return nil
	}
	return v
}
