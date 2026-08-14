package wiki

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"
)

func (s *MySQLStore) LoadSummaries(ctx context.Context, summaryIDs []string) ([]enterprisesummary.StoredSummary, error) {
	out := make([]enterprisesummary.StoredSummary, 0, len(summaryIDs))
	for _, summaryID := range summaryIDs {
		item, err := s.loadSummary(ctx, summaryID)
		if err != nil { return nil, err }
		out = append(out, item)
	}
	return out, nil
}

func (s *MySQLStore) loadSummary(ctx context.Context, summaryID string) (enterprisesummary.StoredSummary, error) {
	var out enterprisesummary.StoredSummary
	err := s.db.QueryRowContext(ctx, `SELECT summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,COALESCE(model,''),created_at FROM summary WHERE summary_id=? AND lifecycle='ACTIVE' AND grounding_status='PASS' AND coverage_status='PASS'`, summaryID).Scan(
		&out.ID, &out.UnitID, &out.VersionID, &out.ObjectKey, &out.ContentHash, &out.GroundingStatus, &out.CoverageStatus, &out.Lifecycle, &out.Model, &out.CreatedAt)
	if err == sql.ErrNoRows { return enterprisesummary.StoredSummary{}, fmt.Errorf("wiki summary %s not found", summaryID) }
	if err != nil { return enterprisesummary.StoredSummary{}, err }
	rows, err := s.db.QueryContext(ctx, `SELECT f.fact_id,f.section,f.fact_text,f.grounding_status,se.evidence_id FROM summary_fact f LEFT JOIN summary_evidence se ON se.fact_id=f.fact_id LEFT JOIN evidence e ON e.evidence_id=se.evidence_id AND e.active=TRUE WHERE f.summary_id=? ORDER BY f.created_at,f.fact_id,se.evidence_id`, summaryID)
	if err != nil { return enterprisesummary.StoredSummary{}, err }
	defer rows.Close()
	facts := map[string]*enterprisesummary.Fact{}
	var order []string
	for rows.Next() {
		var id, section, text, grounding string
		var evidenceID sql.NullString
		if err := rows.Scan(&id, &section, &text, &grounding, &evidenceID); err != nil { return enterprisesummary.StoredSummary{}, err }
		fact := facts[id]
		if fact == nil {
			fact = &enterprisesummary.Fact{ID: id, SummaryID: summaryID, Section: section, FactText: text, GroundingStatus: grounding}
			facts[id] = fact
			order = append(order, id)
		}
		if evidenceID.Valid && strings.TrimSpace(evidenceID.String) != "" { fact.EvidenceIDs = append(fact.EvidenceIDs, evidenceID.String) }
	}
	if err := rows.Err(); err != nil { return enterprisesummary.StoredSummary{}, err }
	for _, id := range order {
		fact := *facts[id]
		fact.EvidenceIDs = uniqueStrings(fact.EvidenceIDs)
		out.Facts = append(out.Facts, fact)
	}
	return out, nil
}
