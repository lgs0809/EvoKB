package promotion

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	"github.com/xoai/sage-wiki/internal/enterprise/retrieval"
	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"

	_ "github.com/go-sql-driver/mysql"
)

const DefaultEffectiveHitThreshold = 3

type MySQLStore struct {
	db        *sql.DB
	threshold int
}

func OpenMySQL(ctx context.Context, dsn string, threshold int) (*MySQLStore, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("promotion mysql open: %w", err)
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("promotion mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("promotion mysql migrate: %w", err)
	}
	if threshold <= 0 {
		threshold = DefaultEffectiveHitThreshold
	}
	return &MySQLStore{db: db, threshold: threshold}, nil
}

func (s *MySQLStore) Close() error { return s.db.Close() }

func (s *MySQLStore) RecordDeliveredSearch(ctx context.Context, query string, filters raw.SearchFilters, result *retrieval.SearchResult) error {
	if s == nil || s.db == nil || result == nil || strings.TrimSpace(result.SearchID) == "" {
		return fmt.Errorf("promotion search audit: store and search result are required")
	}
	filtersJSON, err := json.Marshal(filters)
	if err != nil {
		return fmt.Errorf("promotion search filters: %w", err)
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `INSERT IGNORE INTO search_record(search_id,query_text,filters_json,mode) VALUES(?,?,?,'UNIFIED')`, result.SearchID, query, filtersJSON); err != nil {
		return fmt.Errorf("promotion save search record: %w", err)
	}
	versionIDs := make(map[string]struct{})
	for rank, item := range result.Results {
		evidenceIDs := make([]string, 0, len(item.Evidence))
		for _, evidence := range item.Evidence {
			if evidence.EvidenceID != "" {
				evidenceIDs = append(evidenceIDs, evidence.EvidenceID)
			}
			if evidence.VersionID != "" {
				versionIDs[evidence.VersionID] = struct{}{}
			}
		}
		payload, err := json.Marshal(evidenceIDs)
		if err != nil {
			return fmt.Errorf("promotion search evidence: %w", err)
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO search_result(search_id,rank_no,result_type,result_id,score,evidence_ids,effective) VALUES(?,?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE result_type=VALUES(result_type),result_id=VALUES(result_id),score=VALUES(score),evidence_ids=VALUES(evidence_ids),effective=TRUE`, result.SearchID, rank+1, item.Candidate.ResultType, item.Candidate.ID, item.Candidate.Score, payload); err != nil {
			return fmt.Errorf("promotion save search result: %w", err)
		}
	}
	for versionID := range versionIDs {
		if _, err := tx.ExecContext(ctx, `INSERT IGNORE INTO effective_version_hit(search_id,version_id) VALUES(?,?)`, result.SearchID, versionID); err != nil {
			return fmt.Errorf("promotion save effective hit: %w", err)
		}
		var count int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM effective_version_hit WHERE version_id=?`, versionID).Scan(&count); err != nil {
			return fmt.Errorf("promotion count effective hits: %w", err)
		}
		if count >= s.threshold {
			if err := upsertRecommendation(ctx, tx, versionID, "RUNTIME_EFFECTIVE_HIT", count); err != nil {
				return err
			}
		}
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("promotion search audit commit: %w", err)
	}
	return nil
}

func (s *MySQLStore) RecommendManual(ctx context.Context, versionID string) error {
	return s.recommendImmediate(ctx, versionID, "USER_MARKED")
}

func (s *MySQLStore) RecommendExistingQuestion(ctx context.Context, versionID string) error {
	return s.recommendImmediate(ctx, versionID, "EXISTING_QUESTION_EVIDENCE")
}

func (s *MySQLStore) recommendImmediate(ctx context.Context, versionID, reason string) error {
	if strings.TrimSpace(versionID) == "" {
		return fmt.Errorf("promotion version ID is required")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var lifecycle string
	if err := tx.QueryRowContext(ctx, `SELECT lifecycle FROM document_version WHERE version_id=?`, versionID).Scan(&lifecycle); err != nil {
		return fmt.Errorf("promotion resolve version: %w", err)
	}
	if lifecycle != "ACTIVE" {
		return fmt.Errorf("promotion version %s is not ACTIVE", versionID)
	}
	if err := upsertRecommendation(ctx, tx, versionID, reason, 0); err != nil {
		return err
	}
	return tx.Commit()
}

func upsertRecommendation(ctx context.Context, tx *sql.Tx, versionID, reason string, hits int) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO distill_recommendation(recommendation_id,version_id,reason,distinct_effective_hits,status) VALUES(?,?,?,?, 'RECOMMENDED') ON DUPLICATE KEY UPDATE distinct_effective_hits=GREATEST(distinct_effective_hits,VALUES(distinct_effective_hits)),status=IF(status='DISMISSED',status,'RECOMMENDED')`, newUUID(), versionID, reason, hits)
	if err != nil {
		return fmt.Errorf("promotion upsert recommendation: %w", err)
	}
	return nil
}

func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "00000000-0000-4000-8000-000000000001"
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	x := hex.EncodeToString(b[:])
	return x[0:8] + "-" + x[8:12] + "-" + x[12:16] + "-" + x[16:20] + "-" + x[20:32]
}
