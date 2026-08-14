package graph

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"
	enterprisesummary "github.com/xoai/sage-wiki/internal/enterprise/summary"

	_ "github.com/go-sql-driver/mysql"
)

type MySQLStore struct {
	db *sql.DB
}

func OpenMySQL(ctx context.Context, dsn string) (*MySQLStore, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("graph mysql open: %w", err)
	}
	db.SetMaxOpenConns(12)
	db.SetMaxIdleConns(4)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("graph mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("graph mysql migrate: %w", err)
	}
	return &MySQLStore{db: db}, nil
}

func (s *MySQLStore) Close() error { return s.db.Close() }

func (s *MySQLStore) LoadGroundedSummary(ctx context.Context, summaryID string) (enterprisesummary.StoredSummary, error) {
	var out enterprisesummary.StoredSummary
	var created time.Time
	err := s.db.QueryRowContext(ctx, `
SELECT summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,COALESCE(model,''),created_at
FROM summary
WHERE summary_id=? AND lifecycle='ACTIVE' AND grounding_status='PASS' AND coverage_status='PASS'`, summaryID).Scan(
		&out.ID, &out.UnitID, &out.VersionID, &out.ObjectKey, &out.ContentHash,
		&out.GroundingStatus, &out.CoverageStatus, &out.Lifecycle, &out.Model, &created,
	)
	if err == sql.ErrNoRows {
		return enterprisesummary.StoredSummary{}, fmt.Errorf("graph summary %s: ACTIVE grounded+covered summary not found", summaryID)
	}
	if err != nil {
		return enterprisesummary.StoredSummary{}, fmt.Errorf("graph load summary: %w", err)
	}
	out.CreatedAt = created

	rows, err := s.db.QueryContext(ctx, `
SELECT fact_id,section,fact_text,grounding_status
FROM summary_fact
WHERE summary_id=?
ORDER BY created_at,fact_id`, summaryID)
	if err != nil {
		return enterprisesummary.StoredSummary{}, fmt.Errorf("graph load facts: %w", err)
	}
	for rows.Next() {
		var fact enterprisesummary.Fact
		if err := rows.Scan(&fact.ID, &fact.Section, &fact.FactText, &fact.GroundingStatus); err != nil {
			_ = rows.Close()
			return enterprisesummary.StoredSummary{}, err
		}
		fact.SummaryID = summaryID
		out.Facts = append(out.Facts, fact)
	}
	if err := rows.Close(); err != nil {
		return enterprisesummary.StoredSummary{}, err
	}

	evidenceRows, err := s.db.QueryContext(ctx, `
SELECT se.fact_id,se.evidence_id
FROM summary_evidence se
JOIN evidence e ON e.evidence_id=se.evidence_id
WHERE se.fact_id IN (SELECT fact_id FROM summary_fact WHERE summary_id=?) AND e.active=TRUE
ORDER BY se.fact_id,se.evidence_id`, summaryID)
	if err != nil {
		return enterprisesummary.StoredSummary{}, fmt.Errorf("graph load fact evidence: %w", err)
	}
	byFact := make(map[string][]string)
	for evidenceRows.Next() {
		var factID, evidenceID string
		if err := evidenceRows.Scan(&factID, &evidenceID); err != nil {
			_ = evidenceRows.Close()
			return enterprisesummary.StoredSummary{}, err
		}
		byFact[factID] = append(byFact[factID], evidenceID)
	}
	if err := evidenceRows.Close(); err != nil {
		return enterprisesummary.StoredSummary{}, err
	}
	for i := range out.Facts {
		out.Facts[i].EvidenceIDs = byFact[out.Facts[i].ID]
	}
	return out, nil
}

func (s *MySQLStore) ResolveEntity(ctx context.Context, proposed ProposedEntity) (CanonicalEntity, error) {
	proposed.Name = strings.TrimSpace(proposed.Name)
	proposed.Type = strings.ToUpper(strings.TrimSpace(proposed.Type))
	if err := ValidateEntity(proposed); err != nil {
		return CanonicalEntity{}, err
	}
	key := CanonicalKey(proposed.Name)
	candidateID := stableID("entity", proposed.Type, key)
	if _, err := s.db.ExecContext(ctx, `
INSERT INTO canonical_entity(canonical_entity_id,canonical_name,entity_type,canonical_key)
VALUES(?,?,?,?)
ON DUPLICATE KEY UPDATE canonical_name=VALUES(canonical_name),updated_at=CURRENT_TIMESTAMP(6)`,
		candidateID, proposed.Name, proposed.Type, key); err != nil {
		return CanonicalEntity{}, fmt.Errorf("graph upsert canonical entity: %w", err)
	}
	var out CanonicalEntity
	if err := s.db.QueryRowContext(ctx, `
SELECT canonical_entity_id,canonical_name,entity_type,canonical_key
FROM canonical_entity WHERE entity_type=? AND canonical_key=? LIMIT 1`, proposed.Type, key).Scan(
		&out.ID, &out.CanonicalName, &out.EntityType, &out.CanonicalKey,
	); err != nil {
		return CanonicalEntity{}, fmt.Errorf("graph read canonical entity: %w", err)
	}
	return out, nil
}

func (s *MySQLStore) UpsertRelation(ctx context.Context, relation RelationFact) (RelationFact, error) {
	if strings.TrimSpace(relation.ID) == "" {
		return RelationFact{}, fmt.Errorf("graph relation id is required")
	}
	if _, ok := relationRules[relation.RelationType]; !ok {
		return RelationFact{}, fmt.Errorf("graph relation %s has unsupported type %s", relation.ID, relation.RelationType)
	}
	if relation.ConflictStatus == "" {
		relation.ConflictStatus = "NONE"
	}
	if relation.Lifecycle == "" {
		relation.Lifecycle = "ACTIVE"
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO relation_fact(relation_id,source_entity_id,relation_type,target_entity_id,valid_from,valid_to,conflict_status,lifecycle)
VALUES(?,?,?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE valid_to=VALUES(valid_to),conflict_status=VALUES(conflict_status),lifecycle=VALUES(lifecycle)`,
		relation.ID, relation.SourceEntityID, relation.RelationType, relation.TargetEntityID,
		nullTime(relation.ValidFrom), nullTime(relation.ValidTo), relation.ConflictStatus, relation.Lifecycle)
	if err != nil {
		return RelationFact{}, fmt.Errorf("graph mysql relation: %w", err)
	}
	return relation, nil
}

func (s *MySQLStore) AddSupport(ctx context.Context, support EvidenceSupport) (EvidenceSupport, error) {
	if support.ID == "" || support.RelationID == "" || support.EvidenceID == "" {
		return EvidenceSupport{}, fmt.Errorf("graph support id, relation id and evidence id are required")
	}
	if support.Origin != OriginLLMTriple && support.Origin != OriginWikiKeyword && support.Origin != OriginAliasDerived {
		return EvidenceSupport{}, fmt.Errorf("graph support %s has invalid origin %q", support.ID, support.Origin)
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO relation_evidence_support(support_id,relation_id,origin,evidence_id,summary_id,wiki_id,wiki_version,confidence,active)
VALUES(?,?,?,?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE confidence=VALUES(confidence),active=VALUES(active)`,
		support.ID, support.RelationID, support.Origin, support.EvidenceID, nullString(support.SummaryID),
		nullString(support.WikiID), nullUint64(support.WikiVersion), support.Confidence, support.Active)
	if err != nil {
		return EvidenceSupport{}, fmt.Errorf("graph mysql support: %w", err)
	}
	return support, nil
}

func (s *MySQLStore) DeactivateSupportsForVersion(ctx context.Context, versionID string) error {
	_, err := s.db.ExecContext(ctx, `
UPDATE relation_evidence_support rs
JOIN evidence e ON e.evidence_id=rs.evidence_id
SET rs.active=FALSE
WHERE e.version_id=?`, versionID)
	if err != nil {
		return fmt.Errorf("graph deactivate supports for version: %w", err)
	}
	return nil
}

func nullTime(value *time.Time) any {
	if value == nil {
		return nil
	}
	return value.UTC()
}

func nullString(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return strings.TrimSpace(value)
}

func nullUint64(value *uint64) any {
	if value == nil {
		return nil
	}
	return *value
}
