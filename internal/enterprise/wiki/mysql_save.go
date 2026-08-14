package wiki

import (
	"context"
	"database/sql"
	"fmt"
)

func (s *MySQLStore) SaveWiki(ctx context.Context, wiki StoredWiki) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil { return err }
	defer tx.Rollback()
	if wiki.Lifecycle == "ACTIVE" {
		if _, err := tx.ExecContext(ctx, `UPDATE wiki SET lifecycle='STALE' WHERE wiki_id=? AND wiki_version<>? AND lifecycle='ACTIVE'`, wiki.ID, wiki.Version); err != nil {
			return fmt.Errorf("wiki stale previous: %w", err)
		}
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO wiki(wiki_id,wiki_version,wiki_type,canonical_subject_id,title,object_key,content_hash,lifecycle) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE wiki_type=VALUES(wiki_type),canonical_subject_id=VALUES(canonical_subject_id),title=VALUES(title),object_key=VALUES(object_key),content_hash=VALUES(content_hash),lifecycle=VALUES(lifecycle)`, wiki.ID, wiki.Version, wiki.WikiType, nullableText(wiki.CanonicalSubjectID), wiki.Title, wiki.ObjectKey, wiki.ContentHash, wiki.Lifecycle); err != nil {
		return fmt.Errorf("wiki save header: %w", err)
	}
	for _, claim := range wiki.Claims {
		if err := saveClaim(ctx, tx, claim); err != nil { return err }
	}
	if err := tx.Commit(); err != nil { return fmt.Errorf("wiki commit: %w", err) }
	return nil
}

func saveClaim(ctx context.Context, tx *sql.Tx, claim Claim) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO wiki_claim(claim_id,wiki_id,wiki_version,section,claim_text,grounding_status,temporal_status) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE section=VALUES(section),claim_text=VALUES(claim_text),grounding_status=VALUES(grounding_status),temporal_status=VALUES(temporal_status)`, claim.ID, claim.WikiID, claim.WikiVersion, claim.Section, claim.ClaimText, claim.GroundingStatus, claim.TemporalStatus)
	if err != nil { return fmt.Errorf("wiki save claim: %w", err) }
	for _, factID := range uniqueStrings(claim.SupportingFactIDs) {
		if _, err := tx.ExecContext(ctx, `INSERT IGNORE INTO wiki_claim_fact(claim_id,fact_id) VALUES(?,?)`, claim.ID, factID); err != nil { return fmt.Errorf("wiki save claim fact: %w", err) }
	}
	for _, evidenceID := range uniqueStrings(claim.EvidenceIDs) {
		if _, err := tx.ExecContext(ctx, `INSERT IGNORE INTO wiki_evidence(claim_id,evidence_id) VALUES(?,?)`, claim.ID, evidenceID); err != nil { return fmt.Errorf("wiki save evidence: %w", err) }
	}
	return nil
}

func nullableText(value string) any {
	if value == "" { return nil }
	return value
}
