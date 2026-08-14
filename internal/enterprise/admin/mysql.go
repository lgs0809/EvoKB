package admin

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"

	_ "github.com/go-sql-driver/mysql"
)

type MySQLStore struct{ db *sql.DB }

func OpenMySQL(ctx context.Context, dsn string) (*MySQLStore, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("admin mysql open: %w", err)
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("admin mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("admin mysql migrate: %w", err)
	}
	return &MySQLStore{db: db}, nil
}

func (s *MySQLStore) Close() error { return s.db.Close() }

func (s *MySQLStore) ListDocumentVersions(ctx context.Context, limit int) ([]DocumentVersionView, error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT d.document_id,d.logical_name,v.version_id,v.version_no,v.lifecycle,v.original_filename,
       COALESCE(m.source_department,''),COALESCE(m.material_category,''),COALESCE(m.company,''),
       COALESCE(m.manual_document_type_override,m.auto_document_type,''),m.document_date,v.parsed_at,
       (SELECT COUNT(*) FROM summary s WHERE s.version_id=v.version_id),
       (SELECT COUNT(*) FROM summary s WHERE s.version_id=v.version_id AND s.grounding_status='PASS'),
       (SELECT COUNT(*) FROM summary s WHERE s.version_id=v.version_id AND s.coverage_status='PASS'),
       COALESCE((SELECT dr.status FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
       COALESCE((SELECT dr.reason FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
       COALESCE((SELECT COUNT(*) FROM effective_version_hit evh WHERE evh.version_id=v.version_id),0)
FROM document d
JOIN document_version v ON v.document_id=d.document_id
LEFT JOIN document_metadata m ON m.version_id=v.version_id
WHERE d.status<>'DELETED'
ORDER BY d.updated_at DESC,v.version_no DESC
LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("admin list documents: %w", err)
	}
	defer rows.Close()
	var out []DocumentVersionView
	for rows.Next() {
		var item DocumentVersionView
		var documentDate, parsedAt sql.NullTime
		if err := rows.Scan(
			&item.DocumentID, &item.LogicalName, &item.VersionID, &item.VersionNo, &item.Lifecycle, &item.OriginalFilename,
			&item.SourceDepartment, &item.MaterialCategory, &item.Company, &item.DocumentType,
			&documentDate, &parsedAt, &item.SummaryCount, &item.GroundedSummaries, &item.CoveredSummaries,
			&item.Recommendation, &item.RecommendationReason, &item.EffectiveHits,
		); err != nil {
			return nil, fmt.Errorf("admin list documents scan: %w", err)
		}
		if documentDate.Valid {
			value := documentDate.Time.UTC()
			item.DocumentDate = &value
		}
		if parsedAt.Valid {
			value := parsedAt.Time.UTC()
			item.ParsedAt = &value
		}
		out = append(out, item)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func (s *MySQLStore) ListWikis(ctx context.Context, limit int) ([]WikiView, error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	rows, err := s.db.QueryContext(ctx, `SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki ORDER BY created_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("admin list wikis: %w", err)
	}
	defer rows.Close()
	var out []WikiView
	for rows.Next() {
		var item WikiView
		if err := rows.Scan(&item.WikiID, &item.WikiVersion, &item.WikiType, &item.CanonicalSubjectID, &item.Title, &item.Lifecycle); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *MySQLStore) GetWiki(ctx context.Context, wikiID string, version uint64) (WikiView, error) {
	var out WikiView
	query := `SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki WHERE wiki_id=?`
	args := []any{wikiID}
	if version > 0 {
		query += ` AND wiki_version=?`
		args = append(args, version)
	} else {
		query += ` ORDER BY (lifecycle='ACTIVE') DESC,wiki_version DESC LIMIT 1`
	}
	if err := s.db.QueryRowContext(ctx, query, args...).Scan(&out.WikiID, &out.WikiVersion, &out.WikiType, &out.CanonicalSubjectID, &out.Title, &out.Lifecycle); err != nil {
		return WikiView{}, fmt.Errorf("admin get wiki: %w", err)
	}
	rows, err := s.db.QueryContext(ctx, `SELECT claim_id,section,claim_text,grounding_status,temporal_status FROM wiki_claim WHERE wiki_id=? AND wiki_version=? ORDER BY created_at,claim_id`, out.WikiID, out.WikiVersion)
	if err != nil {
		return WikiView{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var claim ClaimView
		if err := rows.Scan(&claim.ClaimID, &claim.Section, &claim.ClaimText, &claim.GroundingStatus, &claim.TemporalStatus); err != nil {
			return WikiView{}, err
		}
		claim.FactIDs, err = s.claimRefs(ctx, `SELECT fact_id FROM wiki_claim_fact WHERE claim_id=? ORDER BY fact_id`, claim.ClaimID)
		if err != nil {
			return WikiView{}, err
		}
		claim.EvidenceIDs, err = s.claimRefs(ctx, `SELECT evidence_id FROM wiki_evidence WHERE claim_id=? ORDER BY evidence_id`, claim.ClaimID)
		if err != nil {
			return WikiView{}, err
		}
		out.Claims = append(out.Claims, claim)
	}
	return out, rows.Err()
}

func (s *MySQLStore) claimRefs(ctx context.Context, query, claimID string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, query, claimID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var value string
		if err := rows.Scan(&value); err != nil {
			return nil, err
		}
		out = append(out, value)
	}
	return out, rows.Err()
}
