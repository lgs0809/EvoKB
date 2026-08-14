package graph

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
)

func (s *MySQLStore) LoadWikiKeywordClaims(ctx context.Context, wikiID string, wikiVersion uint64) ([]WikiKeywordClaim, error) {
	if s == nil || s.db == nil {
		return nil, fmt.Errorf("wiki keyword mysql store is unavailable")
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT wc.claim_id,wc.claim_text,we.evidence_id
FROM wiki_claim wc
JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version AND w.lifecycle='ACTIVE'
JOIN wiki_evidence we ON we.claim_id=wc.claim_id
JOIN evidence e ON e.evidence_id=we.evidence_id AND e.active=TRUE
WHERE wc.wiki_id=? AND wc.wiki_version=?
  AND wc.grounding_status='PASS' AND wc.temporal_status='PASS'
ORDER BY wc.claim_id,we.evidence_id`, strings.TrimSpace(wikiID), wikiVersion)
	if err != nil {
		return nil, fmt.Errorf("wiki keyword load claims: %w", err)
	}
	defer rows.Close()
	byID := map[string]*WikiKeywordClaim{}
	var order []string
	for rows.Next() {
		var claimID, claimText, evidenceID string
		if err := rows.Scan(&claimID, &claimText, &evidenceID); err != nil {
			return nil, fmt.Errorf("wiki keyword scan claim: %w", err)
		}
		claim := byID[claimID]
		if claim == nil {
			claim = &WikiKeywordClaim{ClaimID: claimID, WikiID: strings.TrimSpace(wikiID), WikiVersion: wikiVersion, ClaimText: claimText}
			byID[claimID] = claim
			order = append(order, claimID)
		}
		claim.EvidenceIDs = append(claim.EvidenceIDs, evidenceID)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("wiki keyword iterate claims: %w", err)
	}
	out := make([]WikiKeywordClaim, 0, len(order))
	for _, claimID := range order {
		claim := byID[claimID]
		claim.EvidenceIDs = uniqueNonEmpty(claim.EvidenceIDs)
		out = append(out, *claim)
	}
	return out, nil
}

func (s *MySQLStore) FindCanonicalEntity(ctx context.Context, canonicalName string) (CanonicalEntity, bool, error) {
	if s == nil || s.db == nil {
		return CanonicalEntity{}, false, fmt.Errorf("wiki keyword mysql store is unavailable")
	}
	key := CanonicalKey(canonicalName)
	if key == "" {
		return CanonicalEntity{}, false, nil
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT canonical_entity_id,canonical_name,entity_type,canonical_key
FROM canonical_entity
WHERE canonical_key=?
ORDER BY canonical_entity_id
LIMIT 2`, key)
	if err != nil {
		return CanonicalEntity{}, false, fmt.Errorf("wiki keyword resolve entity: %w", err)
	}
	defer rows.Close()
	var matches []CanonicalEntity
	for rows.Next() {
		var entity CanonicalEntity
		if err := rows.Scan(&entity.ID, &entity.CanonicalName, &entity.EntityType, &entity.CanonicalKey); err != nil {
			return CanonicalEntity{}, false, fmt.Errorf("wiki keyword scan entity: %w", err)
		}
		matches = append(matches, entity)
	}
	if err := rows.Err(); err != nil {
		return CanonicalEntity{}, false, fmt.Errorf("wiki keyword iterate entities: %w", err)
	}
	if len(matches) != 1 {
		return CanonicalEntity{}, false, nil
	}
	return matches[0], true, nil
}

// EnsureWikiKeywordRelation creates a missing relation but deliberately leaves
// an existing relation untouched. In particular, wiki-derived support must not
// overwrite business valid_from/valid_to or reactivate a stale relation.
func (s *MySQLStore) EnsureWikiKeywordRelation(ctx context.Context, relation RelationFact) (RelationFact, error) {
	if s == nil || s.db == nil {
		return RelationFact{}, fmt.Errorf("wiki keyword mysql store is unavailable")
	}
	if strings.TrimSpace(relation.ID) == "" {
		return RelationFact{}, fmt.Errorf("wiki keyword relation id is required")
	}
	if _, ok := relationRules[relation.RelationType]; !ok {
		return RelationFact{}, fmt.Errorf("wiki keyword relation %s has unsupported type %s", relation.ID, relation.RelationType)
	}
	if relation.ConflictStatus == "" {
		relation.ConflictStatus = "NONE"
	}
	if relation.Lifecycle == "" {
		relation.Lifecycle = "ACTIVE"
	}
	if _, err := s.db.ExecContext(ctx, `
INSERT IGNORE INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,valid_from,valid_to,conflict_status,lifecycle)
VALUES(?,?,?,?,?,?,?,?)`, relation.ID, relation.SourceEntityID, relation.RelationType, relation.TargetEntityID,
		nullTime(relation.ValidFrom), nullTime(relation.ValidTo), relation.ConflictStatus, relation.Lifecycle); err != nil {
		return RelationFact{}, fmt.Errorf("wiki keyword ensure relation: %w", err)
	}
	var validFrom, validTo sql.NullTime
	var out RelationFact
	if err := s.db.QueryRowContext(ctx, `
SELECT relation_id,source_entity_id,relation_type,target_entity_id,valid_from,valid_to,conflict_status,lifecycle
FROM relation_fact WHERE relation_id=?`, relation.ID).Scan(
		&out.ID, &out.SourceEntityID, &out.RelationType, &out.TargetEntityID,
		&validFrom, &validTo, &out.ConflictStatus, &out.Lifecycle,
	); err != nil {
		return RelationFact{}, fmt.Errorf("wiki keyword read relation: %w", err)
	}
	if validFrom.Valid {
		value := validFrom.Time.UTC()
		out.ValidFrom = &value
	}
	if validTo.Valid {
		value := validTo.Time.UTC()
		out.ValidTo = &value
	}
	return out, nil
}
