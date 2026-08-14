package ingest

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"

	_ "github.com/go-sql-driver/mysql"
)

type Store struct{ db *sql.DB }

func OpenMySQL(ctx context.Context, dsn string) (*Store, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("ingest mysql open: %w", err)
	}
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("ingest mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("ingest mysql migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) CreateBatch(ctx context.Context, actorID, actorUsername string, sourceCount int, files []FileDraft) (Batch, error) {
	if len(files) == 0 {
		return Batch{}, fmt.Errorf("ingestion batch requires at least one supported file")
	}
	batchID := uuid.NewString()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Batch{}, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `INSERT INTO ingestion_batch(batch_id,created_by,created_by_username,status,source_count) VALUES(?,?,?,'STAGED',?)`, batchID, nullable(actorID), actorUsername, sourceCount); err != nil {
		return Batch{}, fmt.Errorf("ingestion batch insert: %w", err)
	}
	for _, draft := range files {
		if strings.TrimSpace(draft.LogicalName) == "" {
			draft.LogicalName = draft.OriginalFilename
		}
		tags, _ := json.Marshal(cleanTags(draft.ManualTags))
		if _, err := tx.ExecContext(ctx, `
INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,source_department,material_category,company,document_type,document_date,manual_tags)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, uuid.NewString(), batchID, draft.RelativePath, draft.OriginalFilename, draft.StagingPath, draft.SizeBytes, draft.SHA256, strings.TrimSpace(draft.LogicalName), strings.TrimSpace(draft.SourceDepartment), strings.TrimSpace(draft.MaterialCategory), strings.TrimSpace(draft.Company), strings.TrimSpace(draft.DocumentType), draft.DocumentDate, tags); err != nil {
			return Batch{}, fmt.Errorf("ingestion file insert: %w", err)
		}
	}
	if err := tx.Commit(); err != nil {
		return Batch{}, err
	}
	return s.GetBatch(ctx, batchID)
}

func (s *Store) GetBatch(ctx context.Context, batchID string) (Batch, error) {
	var out Batch
	var actor sql.NullString
	var started, finished sql.NullTime
	err := s.db.QueryRowContext(ctx, `
SELECT b.batch_id,b.created_by,b.created_by_username,b.status,b.source_count,b.created_at,b.updated_at,b.started_at,b.finished_at,
       COUNT(f.file_id),
       COALESCE(SUM(f.status='STAGED'),0),COALESCE(SUM(f.status='QUEUED'),0),COALESCE(SUM(f.status='RUNNING'),0),COALESCE(SUM(f.status='SUCCEEDED'),0),COALESCE(SUM(f.status='FAILED'),0)
FROM ingestion_batch b LEFT JOIN ingestion_file f ON f.batch_id=b.batch_id
WHERE b.batch_id=? GROUP BY b.batch_id`, batchID).Scan(
		&out.BatchID, &actor, &out.CreatedByUsername, &out.Status, &out.SourceCount, &out.CreatedAt, &out.UpdatedAt, &started, &finished,
		&out.FileCount, &out.StagedCount, &out.QueuedCount, &out.RunningCount, &out.SucceededCount, &out.FailedCount,
	)
	if err != nil {
		return Batch{}, err
	}
	if actor.Valid {
		out.CreatedBy = actor.String
	}
	setBatchTimes(&out, started, finished)
	files, err := s.ListFiles(ctx, batchID)
	if err != nil {
		return Batch{}, err
	}
	out.Files = files
	return out, nil
}

func (s *Store) ListBatches(ctx context.Context, opts ListOptions) ([]Batch, int, error) {
	page, pageSize := normalizePage(opts.Page, opts.PageSize)
	where := []string{"1=1"}
	args := []any{}
	if !opts.Admin {
		where = append(where, "b.created_by=?")
		args = append(args, opts.ActorID)
	}
	if v := strings.TrimSpace(opts.Status); v != "" && !strings.EqualFold(v, "ALL") {
		where = append(where, "b.status=?")
		args = append(args, strings.ToUpper(v))
	}
	if q := strings.TrimSpace(opts.Query); q != "" {
		where = append(where, "(b.created_by_username LIKE ? OR EXISTS (SELECT 1 FROM ingestion_file fx WHERE fx.batch_id=b.batch_id AND (fx.logical_name LIKE ? OR fx.original_filename LIKE ? OR fx.relative_path LIKE ?)))")
		like := "%" + q + "%"
		args = append(args, like, like, like, like)
	}
	whereSQL := strings.Join(where, " AND ")
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM ingestion_batch b WHERE `+whereSQL, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	query := `
SELECT b.batch_id,b.created_by,b.created_by_username,b.status,b.source_count,b.created_at,b.updated_at,b.started_at,b.finished_at,
       COUNT(f.file_id),COALESCE(SUM(f.status='STAGED'),0),COALESCE(SUM(f.status='QUEUED'),0),COALESCE(SUM(f.status='RUNNING'),0),COALESCE(SUM(f.status='SUCCEEDED'),0),COALESCE(SUM(f.status='FAILED'),0)
FROM ingestion_batch b LEFT JOIN ingestion_file f ON f.batch_id=b.batch_id
WHERE ` + whereSQL + ` GROUP BY b.batch_id ORDER BY b.created_at DESC LIMIT ? OFFSET ?`
	queryArgs := append(append([]any{}, args...), pageSize, (page-1)*pageSize)
	rows, err := s.db.QueryContext(ctx, query, queryArgs...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []Batch
	for rows.Next() {
		var item Batch
		var actor sql.NullString
		var started, finished sql.NullTime
		if err := rows.Scan(&item.BatchID, &actor, &item.CreatedByUsername, &item.Status, &item.SourceCount, &item.CreatedAt, &item.UpdatedAt, &started, &finished,
			&item.FileCount, &item.StagedCount, &item.QueuedCount, &item.RunningCount, &item.SucceededCount, &item.FailedCount); err != nil {
			return nil, 0, err
		}
		if actor.Valid {
			item.CreatedBy = actor.String
		}
		setBatchTimes(&item, started, finished)
		out = append(out, item)
	}
	return out, total, rows.Err()
}

func (s *Store) ListFiles(ctx context.Context, batchID string) ([]File, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,source_department,material_category,company,document_type,document_date,manual_tags,status,progress_stage,attempt,
       COALESCE(document_id,''),COALESCE(version_id,''),error_code,COALESCE(error_message,''),created_at,updated_at,started_at,finished_at
FROM ingestion_file WHERE batch_id=? ORDER BY created_at,file_id`, batchID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []File
	for rows.Next() {
		item, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if err := s.decorateFiles(ctx, batchID, out); err != nil {
		return nil, err
	}
	return out, nil
}

func (s *Store) UpdateFile(ctx context.Context, fileID string, patch FilePatch) (File, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return File{}, err
	}
	defer tx.Rollback()
	var status string
	if err := tx.QueryRowContext(ctx, `SELECT status FROM ingestion_file WHERE file_id=? FOR UPDATE`, fileID).Scan(&status); err != nil {
		return File{}, err
	}
	if status != "STAGED" {
		return File{}, fmt.Errorf("file metadata can only be edited while STAGED")
	}
	sets := []string{}
	args := []any{}
	add := func(column string, value any) { sets = append(sets, column+"=?"); args = append(args, value) }
	if patch.LogicalName != nil {
		v := strings.TrimSpace(*patch.LogicalName)
		if v == "" {
			return File{}, fmt.Errorf("logical_name cannot be empty")
		}
		add("logical_name", v)
	}
	if patch.SourceDepartment != nil {
		add("source_department", strings.TrimSpace(*patch.SourceDepartment))
	}
	if patch.MaterialCategory != nil {
		add("material_category", strings.TrimSpace(*patch.MaterialCategory))
	}
	if patch.Company != nil {
		add("company", strings.TrimSpace(*patch.Company))
	}
	if patch.DocumentType != nil {
		add("document_type", strings.TrimSpace(*patch.DocumentType))
	}
	if patch.ClearDocumentDate {
		add("document_date", nil)
	} else if patch.DocumentDate != nil {
		add("document_date", *patch.DocumentDate)
	}
	if patch.ManualTags != nil {
		b, _ := json.Marshal(cleanTags(*patch.ManualTags))
		add("manual_tags", b)
	}
	if len(sets) > 0 {
		args = append(args, fileID)
		if _, err := tx.ExecContext(ctx, `UPDATE ingestion_file SET `+strings.Join(sets, ",")+` WHERE file_id=?`, args...); err != nil {
			return File{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return File{}, err
	}
	return s.GetFile(ctx, fileID)
}

func (s *Store) GetFile(ctx context.Context, fileID string) (File, error) {
	row := s.db.QueryRowContext(ctx, `
SELECT file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,source_department,material_category,company,document_type,document_date,manual_tags,status,progress_stage,attempt,
       COALESCE(document_id,''),COALESCE(version_id,''),error_code,COALESCE(error_message,''),created_at,updated_at,started_at,finished_at
FROM ingestion_file WHERE file_id=?`, fileID)
	item, err := scanFile(row)
	if err != nil {
		return File{}, err
	}
	items := []File{item}
	if err := s.decorateFiles(ctx, item.BatchID, items); err != nil {
		return File{}, err
	}
	return items[0], nil
}

func (s *Store) QueueBatch(ctx context.Context, batchID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var status string
	if err := tx.QueryRowContext(ctx, `SELECT status FROM ingestion_batch WHERE batch_id=? FOR UPDATE`, batchID).Scan(&status); err != nil {
		return err
	}
	if status != "STAGED" {
		return fmt.Errorf("batch is %s, expected STAGED", status)
	}
	res, err := tx.ExecContext(ctx, `UPDATE ingestion_file SET status='QUEUED',progress_stage='QUEUED',error_code='',error_message=NULL,finished_at=NULL WHERE batch_id=? AND status='STAGED'`, batchID)
	if err != nil {
		return err
	}
	count, _ := res.RowsAffected()
	if count == 0 {
		return fmt.Errorf("batch contains no staged files")
	}
	if _, err := tx.ExecContext(ctx, `UPDATE ingestion_batch SET status='QUEUED',started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)),finished_at=NULL WHERE batch_id=?`, batchID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) RetryFile(ctx context.Context, fileID string) error {
	res, err := s.db.ExecContext(ctx, `UPDATE ingestion_file SET status='QUEUED',progress_stage='QUEUED',error_code='',error_message=NULL,finished_at=NULL WHERE file_id=? AND status='FAILED'`, fileID)
	if err != nil {
		return err
	}
	count, _ := res.RowsAffected()
	if count != 1 {
		return fmt.Errorf("only FAILED ingestion files can be retried")
	}
	file, err := s.GetFile(ctx, fileID)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `UPDATE ingestion_batch SET status='QUEUED',finished_at=NULL WHERE batch_id=?`, file.BatchID)
	return err
}

func (s *Store) ResetInterrupted(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `UPDATE ingestion_file SET status='QUEUED',progress_stage='QUEUED',error_code='',error_message=NULL,started_at=NULL WHERE status='RUNNING'`); err != nil {
		return err
	}
	_, err := s.db.ExecContext(ctx, `UPDATE ingestion_batch SET status='QUEUED',finished_at=NULL WHERE status='RUNNING'`)
	return err
}

func (s *Store) ClaimNext(ctx context.Context) (*File, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	row := tx.QueryRowContext(ctx, `
SELECT file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,source_department,material_category,company,document_type,document_date,manual_tags,status,progress_stage,attempt,
       COALESCE(document_id,''),COALESCE(version_id,''),error_code,COALESCE(error_message,''),created_at,updated_at,started_at,finished_at
FROM ingestion_file WHERE status='QUEUED' ORDER BY created_at,file_id LIMIT 1 FOR UPDATE SKIP LOCKED`)
	file, err := scanFile(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE ingestion_file SET status='RUNNING',progress_stage='STARTING',attempt=attempt+1,started_at=CURRENT_TIMESTAMP(6),finished_at=NULL WHERE file_id=?`, file.FileID); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE ingestion_batch SET status='RUNNING',started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)),finished_at=NULL WHERE batch_id=?`, file.BatchID); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	file.Status = "RUNNING"
	file.ProgressStage = "STARTING"
	file.Attempt++
	return &file, nil
}

func (s *Store) UpdateProgress(ctx context.Context, fileID, stage string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE ingestion_file SET progress_stage=? WHERE file_id=? AND status='RUNNING'`, strings.ToUpper(strings.TrimSpace(stage)), fileID)
	return err
}

func (s *Store) Complete(ctx context.Context, fileID, documentID, versionID string) error {
	file, err := s.GetFile(ctx, fileID)
	if err != nil {
		return err
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE ingestion_file SET status='SUCCEEDED',progress_stage='SUCCEEDED',document_id=?,version_id=?,error_code='',error_message=NULL,finished_at=CURRENT_TIMESTAMP(6) WHERE file_id=?`, documentID, versionID, fileID); err != nil {
		return err
	}
	return s.recalculateBatch(ctx, file.BatchID)
}

func (s *Store) Fail(ctx context.Context, fileID, code, message string) error {
	file, err := s.GetFile(ctx, fileID)
	if err != nil {
		return err
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE ingestion_file SET status='FAILED',progress_stage='FAILED',error_code=?,error_message=?,finished_at=CURRENT_TIMESTAMP(6) WHERE file_id=?`, code, truncate(message, 8000), fileID); err != nil {
		return err
	}
	return s.recalculateBatch(ctx, file.BatchID)
}

func (s *Store) recalculateBatch(ctx context.Context, batchID string) error {
	var staged, queued, running, succeeded, failed int
	if err := s.db.QueryRowContext(ctx, `SELECT COALESCE(SUM(status='STAGED'),0),COALESCE(SUM(status='QUEUED'),0),COALESCE(SUM(status='RUNNING'),0),COALESCE(SUM(status='SUCCEEDED'),0),COALESCE(SUM(status='FAILED'),0) FROM ingestion_file WHERE batch_id=?`, batchID).Scan(&staged, &queued, &running, &succeeded, &failed); err != nil {
		return err
	}
	status := "STAGED"
	terminal := false
	switch {
	case running > 0:
		status = "RUNNING"
	case queued > 0:
		status = "QUEUED"
	case staged > 0 && succeeded == 0 && failed == 0:
		status = "STAGED"
	case failed > 0 && succeeded > 0:
		status, terminal = "PARTIAL", true
	case failed > 0:
		status, terminal = "FAILED", true
	case succeeded > 0:
		status, terminal = "SUCCEEDED", true
	}
	if terminal {
		_, err := s.db.ExecContext(ctx, `UPDATE ingestion_batch SET status=?,finished_at=CURRENT_TIMESTAMP(6) WHERE batch_id=?`, status, batchID)
		return err
	}
	_, err := s.db.ExecContext(ctx, `UPDATE ingestion_batch SET status=?,finished_at=NULL WHERE batch_id=?`, status, batchID)
	return err
}

func (s *Store) decorateFiles(ctx context.Context, batchID string, items []File) error {
	if len(items) == 0 {
		return nil
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT f.file_id,
       COALESCE((SELECT v.version_id FROM document_version v WHERE v.sha256=f.sha256 ORDER BY v.created_at DESC LIMIT 1),''),
       COALESCE((SELECT v.document_id FROM document_version v WHERE v.sha256=f.sha256 ORDER BY v.created_at DESC LIMIT 1),''),
       COALESCE((SELECT d.logical_name FROM document d JOIN document_version v ON v.document_id=d.document_id WHERE v.sha256=f.sha256 ORDER BY v.created_at DESC LIMIT 1),''),
       COALESCE((SELECT v.lifecycle FROM document_version v WHERE v.sha256=f.sha256 ORDER BY v.created_at DESC LIMIT 1),''),
       COALESCE((SELECT d.document_id FROM document d WHERE d.logical_name=f.logical_name AND d.status='ACTIVE' ORDER BY d.created_at LIMIT 1),''),
       COALESCE((SELECT MAX(v.version_no) FROM document d JOIN document_version v ON v.document_id=d.document_id WHERE d.logical_name=f.logical_name AND d.status='ACTIVE'),0)
FROM ingestion_file f WHERE f.batch_id=?`, batchID)
	if err != nil {
		return fmt.Errorf("ingestion decorate files: %w", err)
	}
	defer rows.Close()
	type hint struct {
		DuplicateVersionID        string
		DuplicateDocumentID       string
		DuplicateLogicalName      string
		DuplicateVersionLifecycle string
		ExistingDocumentID        string
		ExistingVersionNo         uint64
	}
	hints := make(map[string]hint, len(items))
	for rows.Next() {
		var fileID string
		var value hint
		if err := rows.Scan(&fileID, &value.DuplicateVersionID, &value.DuplicateDocumentID, &value.DuplicateLogicalName, &value.DuplicateVersionLifecycle, &value.ExistingDocumentID, &value.ExistingVersionNo); err != nil {
			return err
		}
		hints[fileID] = value
	}
	if err := rows.Err(); err != nil {
		return err
	}
	for i := range items {
		value := hints[items[i].FileID]
		items[i].DuplicateVersionID = value.DuplicateVersionID
		items[i].DuplicateDocumentID = value.DuplicateDocumentID
		items[i].DuplicateLogicalName = value.DuplicateLogicalName
		items[i].DuplicateVersionLifecycle = value.DuplicateVersionLifecycle
		items[i].ExistingDocumentID = value.ExistingDocumentID
		items[i].ExistingVersionNo = value.ExistingVersionNo
		switch {
		case value.DuplicateVersionID != "" && value.ExistingDocumentID != "" && value.DuplicateDocumentID == value.ExistingDocumentID:
			items[i].ExpectedBehavior = "REUSE_VERSION"
		case value.DuplicateVersionID != "":
			items[i].ExpectedBehavior = "DUPLICATE_CONTENT"
		case value.ExistingDocumentID != "":
			items[i].ExpectedBehavior = "CREATE_VERSION"
		default:
			items[i].ExpectedBehavior = "CREATE_DOCUMENT"
		}
	}
	return nil
}

func normalizePage(page, pageSize int) (int, int) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 {
		pageSize = 30
	}
	if pageSize > 100 {
		pageSize = 100
	}
	return page, pageSize
}

func scanFile(scanner interface{ Scan(...any) error }) (File, error) {
	var item File
	var date, started, finished sql.NullTime
	var tags []byte
	if err := scanner.Scan(&item.FileID, &item.BatchID, &item.RelativePath, &item.OriginalFilename, &item.StagingPath, &item.SizeBytes, &item.SHA256, &item.LogicalName,
		&item.SourceDepartment, &item.MaterialCategory, &item.Company, &item.DocumentType, &date, &tags, &item.Status, &item.ProgressStage, &item.Attempt,
		&item.DocumentID, &item.VersionID, &item.ErrorCode, &item.ErrorMessage, &item.CreatedAt, &item.UpdatedAt, &started, &finished); err != nil {
		return File{}, err
	}
	if date.Valid {
		value := date.Time.UTC()
		item.DocumentDate = &value
	}
	if started.Valid {
		value := started.Time.UTC()
		item.StartedAt = &value
	}
	if finished.Valid {
		value := finished.Time.UTC()
		item.FinishedAt = &value
	}
	item.CreatedAt = item.CreatedAt.UTC()
	item.UpdatedAt = item.UpdatedAt.UTC()
	_ = json.Unmarshal(tags, &item.ManualTags)
	return item, nil
}

func setBatchTimes(item *Batch, started, finished sql.NullTime) {
	item.CreatedAt = item.CreatedAt.UTC()
	item.UpdatedAt = item.UpdatedAt.UTC()
	if started.Valid {
		value := started.Time.UTC()
		item.StartedAt = &value
	}
	if finished.Valid {
		value := finished.Time.UTC()
		item.FinishedAt = &value
	}
}

func cleanTags(values []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	return out
}

func nullable(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return value
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
}
