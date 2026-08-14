package admin

import (
	"context"
	"database/sql"
	"fmt"
)

func (s *MySQLStore) Overview(ctx context.Context) (OverviewView, error) {
	var out OverviewView
	err := s.db.QueryRowContext(ctx, `
SELECT
  (SELECT COUNT(*) FROM document WHERE status='ACTIVE'),
  (SELECT COUNT(*) FROM document_version),
  (SELECT COUNT(*) FROM document_version WHERE lifecycle='ACTIVE'),
  (SELECT COUNT(*) FROM document_version WHERE lifecycle='PENDING'),
  (SELECT COUNT(*) FROM document_version WHERE lifecycle='FAILED'),
  (SELECT COUNT(*) FROM raw_block),
  (SELECT COUNT(*) FROM evidence WHERE active=TRUE),
  (SELECT COUNT(*) FROM summary WHERE lifecycle='ACTIVE'),
  (SELECT COUNT(*) FROM summary WHERE lifecycle='STALE'),
  (SELECT COUNT(*) FROM summary WHERE lifecycle='ACTIVE' AND grounding_status='PASS'),
  (SELECT COUNT(*) FROM summary WHERE lifecycle='ACTIVE' AND coverage_status='PASS'),
  (SELECT COUNT(*) FROM wiki WHERE lifecycle='ACTIVE'),
  (SELECT COUNT(*) FROM wiki WHERE lifecycle='STALE'),
  (SELECT COUNT(DISTINCT rf.relation_id) FROM relation_fact rf JOIN relation_evidence_support rs ON rs.relation_id=rf.relation_id AND rs.active=TRUE WHERE rf.lifecycle='ACTIVE'),
  (SELECT COUNT(*) FROM relation_evidence_support WHERE active=TRUE),
  (SELECT COUNT(*) FROM effective_version_hit),
  (SELECT COUNT(DISTINCT version_id) FROM distill_recommendation WHERE status='RECOMMENDED'),
  (SELECT COUNT(*) FROM compile_task WHERE status='PENDING'),
  (SELECT COUNT(*) FROM compile_task WHERE status='RUNNING'),
  (SELECT COUNT(*) FROM compile_task WHERE status='FAILED')`).Scan(
		&out.Documents,
		&out.Versions,
		&out.ActiveVersions,
		&out.PendingVersions,
		&out.FailedVersions,
		&out.RawBlocks,
		&out.ActiveEvidence,
		&out.ActiveSummaries,
		&out.StaleSummaries,
		&out.GroundedSummaries,
		&out.CoveredSummaries,
		&out.ActiveWikis,
		&out.StaleWikis,
		&out.ActiveRelations,
		&out.ActiveGraphSupports,
		&out.EffectiveHits,
		&out.RecommendedSources,
		&out.PendingTasks,
		&out.RunningTasks,
		&out.FailedTasks,
	)
	if err != nil {
		return OverviewView{}, fmt.Errorf("admin overview: %w", err)
	}
	return out, nil
}

func (s *MySQLStore) ListCompileTasks(ctx context.Context, limit int) ([]CompileTaskView, error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT ct.task_id,COALESCE(ct.version_id,''),COALESCE(d.logical_name,''),ct.task_type,ct.status,ct.attempt,
       COALESCE(ct.error_code,''),COALESCE(ct.error_message,''),ct.started_at,ct.finished_at,ct.created_at
FROM compile_task ct
LEFT JOIN document_version v ON v.version_id=ct.version_id
LEFT JOIN document d ON d.document_id=v.document_id
ORDER BY ct.created_at DESC
LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("admin list compile tasks: %w", err)
	}
	defer rows.Close()
	var out []CompileTaskView
	for rows.Next() {
		var item CompileTaskView
		var startedAt, finishedAt sql.NullTime
		if err := rows.Scan(
			&item.TaskID, &item.VersionID, &item.LogicalName, &item.TaskType, &item.Status, &item.Attempt,
			&item.ErrorCode, &item.ErrorMessage, &startedAt, &finishedAt, &item.CreatedAt,
		); err != nil {
			return nil, fmt.Errorf("admin list compile tasks scan: %w", err)
		}
		if startedAt.Valid {
			value := startedAt.Time.UTC()
			item.StartedAt = &value
		}
		if finishedAt.Valid {
			value := finishedAt.Time.UTC()
			item.FinishedAt = &value
		}
		item.CreatedAt = item.CreatedAt.UTC()
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *MySQLStore) ListEvidence(ctx context.Context, limit int) ([]EvidenceListView, error) {
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT e.evidence_id,e.document_id,d.logical_name,e.version_id,v.version_no,v.original_filename,
       e.block_id,rb.block_type,e.raw_location,LEFT(rb.raw_content,320),e.active,e.created_at
FROM evidence e
JOIN document d ON d.document_id=e.document_id
JOIN document_version v ON v.version_id=e.version_id
JOIN raw_block rb ON rb.block_id=e.block_id
WHERE d.status<>'DELETED'
ORDER BY e.active DESC,e.created_at DESC
LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("admin list evidence: %w", err)
	}
	defer rows.Close()
	var out []EvidenceListView
	for rows.Next() {
		var item EvidenceListView
		var rawLocation []byte
		if err := rows.Scan(
			&item.EvidenceID, &item.DocumentID, &item.LogicalName, &item.VersionID, &item.VersionNo,
			&item.OriginalFilename, &item.BlockID, &item.BlockType, &rawLocation, &item.Excerpt, &item.Active, &item.CreatedAt,
		); err != nil {
			return nil, fmt.Errorf("admin list evidence scan: %w", err)
		}
		item.RawLocation = append(item.RawLocation[:0], rawLocation...)
		item.CreatedAt = item.CreatedAt.UTC()
		out = append(out, item)
	}
	return out, rows.Err()
}
