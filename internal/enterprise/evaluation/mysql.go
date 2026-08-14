package evaluation

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

// MySQLStore reads the actual compiled artifacts used by the quality evaluator.
// It is intentionally read-only at the API level.
type MySQLStore struct {
	db *sql.DB
}

func OpenMySQL(ctx context.Context, dsn string) (*MySQLStore, error) {
	dsn = strings.TrimSpace(dsn)
	if dsn == "" {
		return nil, fmt.Errorf("evaluation mysql DSN is required")
	}
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("evaluation open mysql: %w", err)
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(5 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("evaluation ping mysql: %w", err)
	}
	return &MySQLStore{db: db}, nil
}

func (s *MySQLStore) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *MySQLStore) Load(ctx context.Context, versionIDs []string) (ActualArtifacts, error) {
	if s == nil || s.db == nil {
		return ActualArtifacts{}, fmt.Errorf("evaluation mysql store is unavailable")
	}
	clause, args, err := versionClause(versionIDs)
	if err != nil {
		return ActualArtifacts{}, err
	}
	var actual ActualArtifacts
	if actual.SummaryFacts, err = s.loadSummaryFacts(ctx, clause, args); err != nil {
		return ActualArtifacts{}, err
	}
	if actual.Relations, err = s.loadRelations(ctx, clause, args); err != nil {
		return ActualArtifacts{}, err
	}
	if actual.EntityMappings, err = s.loadEntityMappings(ctx, clause, args); err != nil {
		return ActualArtifacts{}, err
	}
	if actual.WikiClaims, err = s.loadWikiClaims(ctx, clause, args); err != nil {
		return ActualArtifacts{}, err
	}
	return actual, nil
}

func (s *MySQLStore) loadSummaryFacts(ctx context.Context, clause string, args []any) ([]string, error) {
	query := `SELECT DISTINCT sf.fact_text
FROM summary_fact sf
JOIN summary sm ON sm.summary_id=sf.summary_id
WHERE sm.lifecycle='ACTIVE' AND sm.version_id IN (` + clause + `)
ORDER BY sf.fact_text`
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("evaluation load summary facts: %w", err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var value string
		if err := rows.Scan(&value); err != nil {
			return nil, fmt.Errorf("evaluation scan summary fact: %w", err)
		}
		out = append(out, value)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("evaluation iterate summary facts: %w", err)
	}
	return out, nil
}

func (s *MySQLStore) loadRelations(ctx context.Context, clause string, args []any) ([]RelationActual, error) {
	query := `SELECT DISTINCT src.canonical_name, rf.relation_type, dst.canonical_name
FROM relation_fact rf
JOIN canonical_entity src ON src.canonical_entity_id=rf.source_entity_id
JOIN canonical_entity dst ON dst.canonical_entity_id=rf.target_entity_id
JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE
JOIN evidence e ON e.evidence_id=rs.evidence_id AND e.active=TRUE
WHERE rf.lifecycle='ACTIVE' AND e.version_id IN (` + clause + `)
ORDER BY src.canonical_name, rf.relation_type, dst.canonical_name`
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("evaluation load graph relations: %w", err)
	}
	defer rows.Close()
	var out []RelationActual
	for rows.Next() {
		var item RelationActual
		if err := rows.Scan(&item.Source, &item.RelationType, &item.Target); err != nil {
			return nil, fmt.Errorf("evaluation scan graph relation: %w", err)
		}
		out = append(out, item)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("evaluation iterate graph relations: %w", err)
	}
	return out, nil
}

func (s *MySQLStore) loadEntityMappings(ctx context.Context, clause string, args []any) ([]EntityMappingActual, error) {
	query := `SELECT DISTINCT se.surface_form, se.entity_type, ce.canonical_name
FROM source_entity se
JOIN entity_mapping em ON em.source_entity_id=se.source_entity_id AND em.status='APPLIED'
JOIN canonical_entity ce ON ce.canonical_entity_id=em.canonical_entity_id
WHERE se.version_id IN (` + clause + `)
ORDER BY se.surface_form, se.entity_type, ce.canonical_name`
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("evaluation load entity mappings: %w", err)
	}
	defer rows.Close()
	var out []EntityMappingActual
	for rows.Next() {
		var item EntityMappingActual
		if err := rows.Scan(&item.SurfaceForm, &item.EntityType, &item.CanonicalName); err != nil {
			return nil, fmt.Errorf("evaluation scan entity mapping: %w", err)
		}
		out = append(out, item)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("evaluation iterate entity mappings: %w", err)
	}
	return out, nil
}

func (s *MySQLStore) loadWikiClaims(ctx context.Context, clause string, args []any) ([]WikiClaimActual, error) {
	query := `SELECT wc.claim_id, wc.claim_text, wc.grounding_status,
       MAX(CASE WHEN e.active=TRUE THEN 1 ELSE 0 END) AS has_active_evidence
FROM wiki_claim wc
JOIN wiki w ON w.wiki_id=wc.wiki_id AND w.wiki_version=wc.wiki_version AND w.lifecycle='ACTIVE'
JOIN wiki_evidence we ON we.claim_id=wc.claim_id
JOIN evidence e ON e.evidence_id=we.evidence_id
WHERE e.version_id IN (` + clause + `)
GROUP BY wc.claim_id, wc.claim_text, wc.grounding_status
ORDER BY wc.claim_id`
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("evaluation load wiki claims: %w", err)
	}
	defer rows.Close()
	var out []WikiClaimActual
	for rows.Next() {
		var claimID, text, grounding string
		var hasActiveEvidence int
		if err := rows.Scan(&claimID, &text, &grounding, &hasActiveEvidence); err != nil {
			return nil, fmt.Errorf("evaluation scan wiki claim: %w", err)
		}
		_ = claimID
		out = append(out, WikiClaimActual{Text: text, Grounded: strings.EqualFold(grounding, "PASS") && hasActiveEvidence > 0})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("evaluation iterate wiki claims: %w", err)
	}
	return out, nil
}

func versionClause(versionIDs []string) (string, []any, error) {
	seen := make(map[string]struct{}, len(versionIDs))
	placeholders := make([]string, 0, len(versionIDs))
	args := make([]any, 0, len(versionIDs))
	for _, versionID := range versionIDs {
		versionID = strings.TrimSpace(versionID)
		if versionID == "" {
			continue
		}
		if _, ok := seen[versionID]; ok {
			continue
		}
		seen[versionID] = struct{}{}
		placeholders = append(placeholders, "?")
		args = append(args, versionID)
	}
	if len(args) == 0 {
		return "", nil, fmt.Errorf("evaluation requires at least one version_id")
	}
	return strings.Join(placeholders, ","), args, nil
}
