package admin

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
)

type PageQuery struct {
	Page               int
	PageSize           int
	Query              string
	Lifecycle          string
	Status             string
	Company            string
	SourceDepartment   string
	DocumentType       string
	MaterialCategory   string
	DocumentID         string
	WikiType           string
	WikiID             string
	CanonicalSubjectID string
	ActorID            string
	Admin              bool
	Active             *bool
}

type FacetsView struct {
	Companies          []string `json:"companies"`
	SourceDepartments  []string `json:"source_departments"`
	DocumentTypes      []string `json:"document_types"`
	MaterialCategories []string `json:"material_categories"`
}

func (s *MySQLStore) ListDocumentVersionsPage(ctx context.Context, q PageQuery) ([]DocumentVersionView, int, error) {
	page, pageSize := normalizeAdminPage(q.Page, q.PageSize)
	where := []string{"d.status<>'DELETED'"}
	args := []any{}
	if value := strings.TrimSpace(q.Query); value != "" {
		like := "%" + value + "%"
		where = append(where, "(d.logical_name LIKE ? OR v.original_filename LIKE ? OR COALESCE(m.company,'') LIKE ? OR COALESCE(m.source_department,'') LIKE ? OR COALESCE(m.manual_document_type_override,m.auto_document_type,'') LIKE ?)")
		args = append(args, like, like, like, like, like)
	}
	appendEq := func(column, value string) {
		if value = strings.TrimSpace(value); value != "" && !strings.EqualFold(value, "ALL") {
			where = append(where, column+"=?")
			args = append(args, value)
		}
	}
	appendEq("v.lifecycle", strings.ToUpper(q.Lifecycle))
	appendEq("m.company", q.Company)
	appendEq("m.source_department", q.SourceDepartment)
	appendEq("COALESCE(m.manual_document_type_override,m.auto_document_type,'')", q.DocumentType)
	appendEq("m.material_category", q.MaterialCategory)
	appendEq("d.document_id", q.DocumentID)
	whereSQL := strings.Join(where, " AND ")
	var total int
	countQuery := `SELECT COUNT(*) FROM document d JOIN document_version v ON v.document_id=d.document_id LEFT JOIN document_metadata m ON m.version_id=v.version_id WHERE ` + whereSQL
	if err := s.db.QueryRowContext(ctx, countQuery, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("admin count documents: %w", err)
	}
	query := `
SELECT d.document_id,d.logical_name,v.version_id,v.version_no,v.lifecycle,v.original_filename,
       COALESCE(m.source_department,''),COALESCE(m.material_category,''),COALESCE(m.company,''),
       COALESCE(m.manual_document_type_override,m.auto_document_type,''),m.document_date,v.parsed_at,
       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id),
       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.grounding_status='PASS'),
       (SELECT COUNT(*) FROM summary sx WHERE sx.version_id=v.version_id AND sx.coverage_status='PASS'),
       COALESCE((SELECT dr.status FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
       COALESCE((SELECT dr.reason FROM distill_recommendation dr WHERE dr.version_id=v.version_id ORDER BY (dr.status='RECOMMENDED') DESC,dr.created_at DESC LIMIT 1),''),
       COALESCE((SELECT COUNT(*) FROM effective_version_hit evh WHERE evh.version_id=v.version_id),0)
FROM document d JOIN document_version v ON v.document_id=d.document_id
LEFT JOIN document_metadata m ON m.version_id=v.version_id
WHERE ` + whereSQL + ` ORDER BY d.updated_at DESC,v.version_no DESC LIMIT ? OFFSET ?`
	queryArgs := append(append([]any{}, args...), pageSize, (page-1)*pageSize)
	rows, err := s.db.QueryContext(ctx, query, queryArgs...)
	if err != nil {
		return nil, 0, fmt.Errorf("admin list documents page: %w", err)
	}
	defer rows.Close()
	var out []DocumentVersionView
	for rows.Next() {
		var item DocumentVersionView
		var documentDate, parsedAt sql.NullTime
		if err := rows.Scan(&item.DocumentID, &item.LogicalName, &item.VersionID, &item.VersionNo, &item.Lifecycle, &item.OriginalFilename,
			&item.SourceDepartment, &item.MaterialCategory, &item.Company, &item.DocumentType, &documentDate, &parsedAt, &item.SummaryCount, &item.GroundedSummaries, &item.CoveredSummaries, &item.Recommendation, &item.RecommendationReason, &item.EffectiveHits); err != nil {
			return nil, 0, err
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
	return out, total, rows.Err()
}

func (s *MySQLStore) ListWikisPage(ctx context.Context, q PageQuery) ([]WikiView, int, error) {
	page, pageSize := normalizeAdminPage(q.Page, q.PageSize)
	where := []string{"1=1"}
	args := []any{}
	if value := strings.TrimSpace(q.Query); value != "" {
		like := "%" + value + "%"
		where = append(where, "(title LIKE ? OR wiki_type LIKE ?)")
		args = append(args, like, like)
	}
	if value := strings.TrimSpace(q.Lifecycle); value != "" && !strings.EqualFold(value, "ALL") {
		where = append(where, "lifecycle=?")
		args = append(args, strings.ToUpper(value))
	}
	if value := strings.TrimSpace(q.WikiType); value != "" && !strings.EqualFold(value, "ALL") {
		where = append(where, "wiki_type=?")
		args = append(args, value)
	}
	if value := strings.TrimSpace(q.WikiID); value != "" {
		where = append(where, "wiki_id=?")
		args = append(args, value)
	}
	if value := strings.TrimSpace(q.CanonicalSubjectID); value != "" {
		where = append(where, "canonical_subject_id=?")
		args = append(args, value)
	}
	whereSQL := strings.Join(where, " AND ")
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM wiki WHERE `+whereSQL, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT wiki_id,wiki_version,wiki_type,COALESCE(canonical_subject_id,''),title,lifecycle FROM wiki WHERE `+whereSQL+` ORDER BY created_at DESC LIMIT ? OFFSET ?`, append(append([]any{}, args...), pageSize, (page-1)*pageSize)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []WikiView
	for rows.Next() {
		var item WikiView
		if err := rows.Scan(&item.WikiID, &item.WikiVersion, &item.WikiType, &item.CanonicalSubjectID, &item.Title, &item.Lifecycle); err != nil {
			return nil, 0, err
		}
		out = append(out, item)
	}
	return out, total, rows.Err()
}

func (s *MySQLStore) ListEvidencePage(ctx context.Context, q PageQuery) ([]EvidenceListView, int, error) {
	page, pageSize := normalizeAdminPage(q.Page, q.PageSize)
	where := []string{"d.status<>'DELETED'"}
	args := []any{}
	if q.Active != nil {
		where = append(where, "e.active=?")
		args = append(args, *q.Active)
	}
	if value := strings.TrimSpace(q.Query); value != "" {
		like := "%" + value + "%"
		where = append(where, "(d.logical_name LIKE ? OR v.original_filename LIKE ? OR e.evidence_id LIKE ? OR e.version_id LIKE ? OR e.block_id LIKE ? OR rb.raw_content LIKE ?)")
		args = append(args, like, like, like, like, like, like)
	}
	whereSQL := strings.Join(where, " AND ")
	base := ` FROM evidence e JOIN document d ON d.document_id=e.document_id JOIN document_version v ON v.version_id=e.version_id JOIN raw_block rb ON rb.block_id=e.block_id WHERE ` + whereSQL
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*)`+base, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT e.evidence_id,e.document_id,d.logical_name,e.version_id,v.version_no,v.original_filename,e.block_id,rb.block_type,e.raw_location,LEFT(rb.raw_content,320),e.active,e.created_at`+base+` ORDER BY e.active DESC,e.created_at DESC LIMIT ? OFFSET ?`, append(append([]any{}, args...), pageSize, (page-1)*pageSize)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []EvidenceListView
	for rows.Next() {
		var item EvidenceListView
		var loc []byte
		if err := rows.Scan(&item.EvidenceID, &item.DocumentID, &item.LogicalName, &item.VersionID, &item.VersionNo, &item.OriginalFilename, &item.BlockID, &item.BlockType, &loc, &item.Excerpt, &item.Active, &item.CreatedAt); err != nil {
			return nil, 0, err
		}
		item.RawLocation = append(item.RawLocation[:0], loc...)
		item.CreatedAt = item.CreatedAt.UTC()
		out = append(out, item)
	}
	return out, total, rows.Err()
}

func (s *MySQLStore) ListCompileTasksPage(ctx context.Context, q PageQuery) ([]CompileTaskView, int, error) {
	page, pageSize := normalizeAdminPage(q.Page, q.PageSize)
	where := []string{"1=1"}
	args := []any{}
	if value := strings.TrimSpace(q.Status); value != "" && !strings.EqualFold(value, "ALL") {
		where = append(where, "ct.status=?")
		args = append(args, strings.ToUpper(value))
	}
	if value := strings.TrimSpace(q.Query); value != "" {
		like := "%" + value + "%"
		where = append(where, "(COALESCE(d.logical_name,'') LIKE ? OR ct.task_id LIKE ? OR COALESCE(ct.version_id,'') LIKE ? OR ct.task_type LIKE ? OR COALESCE(ct.error_message,'') LIKE ?)")
		args = append(args, like, like, like, like, like)
	}
	whereSQL := strings.Join(where, " AND ")
	joins := ` FROM compile_task ct LEFT JOIN document_version v ON v.version_id=ct.version_id LEFT JOIN document d ON d.document_id=v.document_id WHERE ` + whereSQL
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*)`+joins, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT ct.task_id,COALESCE(ct.version_id,''),COALESCE(d.logical_name,''),ct.task_type,ct.status,ct.attempt,COALESCE(ct.error_code,''),COALESCE(ct.error_message,''),ct.started_at,ct.finished_at,ct.created_at`+joins+` ORDER BY ct.created_at DESC LIMIT ? OFFSET ?`, append(append([]any{}, args...), pageSize, (page-1)*pageSize)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []CompileTaskView
	for rows.Next() {
		var item CompileTaskView
		var started, finished sql.NullTime
		if err := rows.Scan(&item.TaskID, &item.VersionID, &item.LogicalName, &item.TaskType, &item.Status, &item.Attempt, &item.ErrorCode, &item.ErrorMessage, &started, &finished, &item.CreatedAt); err != nil {
			return nil, 0, err
		}
		if started.Valid {
			v := started.Time.UTC()
			item.StartedAt = &v
		}
		if finished.Valid {
			v := finished.Time.UTC()
			item.FinishedAt = &v
		}
		item.CreatedAt = item.CreatedAt.UTC()
		out = append(out, item)
	}
	return out, total, rows.Err()
}

func (s *MySQLStore) ListTasksPage(ctx context.Context, q PageQuery) ([]CompileTaskView, int, error) {
	page, pageSize := normalizeAdminPage(q.Page, q.PageSize)
	where := []string{"1=1"}
	filterArgs := []any{}
	if value := strings.TrimSpace(q.Status); value != "" && !strings.EqualFold(value, "ALL") {
		where = append(where, "u.status=?")
		filterArgs = append(filterArgs, strings.ToUpper(value))
	}
	if value := strings.TrimSpace(q.Query); value != "" {
		like := "%" + value + "%"
		where = append(where, "(u.logical_name LIKE ? OR u.task_id LIKE ? OR u.version_id LIKE ? OR u.task_type LIKE ? OR u.error_message LIKE ? OR u.progress_stage LIKE ?)")
		filterArgs = append(filterArgs, like, like, like, like, like, like)
	}

	unionArgs := []any{}
	union := `(
SELECT ct.task_id,'COMPILE' AS source,'' AS batch_id,'' AS file_id,COALESCE(ct.version_id,'') AS version_id,COALESCE(d.logical_name,'') AS logical_name,
       ct.task_type,ct.status,ct.status AS progress_stage,ct.attempt,COALESCE(ct.error_code,'') AS error_code,COALESCE(ct.error_message,'') AS error_message,ct.started_at,ct.finished_at,ct.created_at
FROM compile_task ct LEFT JOIN document_version v ON v.version_id=ct.version_id LEFT JOIN document d ON d.document_id=v.document_id
UNION ALL
SELECT f.file_id AS task_id,'INGEST' AS source,f.batch_id,f.file_id,COALESCE(f.version_id,'') AS version_id,f.logical_name,
       'INGEST' AS task_type,f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,'') AS error_message,f.started_at,f.finished_at,f.created_at
FROM ingestion_file f
) u`
	if !q.Admin {
		union = `(
SELECT f.file_id AS task_id,'INGEST' AS source,f.batch_id,f.file_id,COALESCE(f.version_id,'') AS version_id,f.logical_name,
       'INGEST' AS task_type,f.status,f.progress_stage,f.attempt,f.error_code,COALESCE(f.error_message,'') AS error_message,f.started_at,f.finished_at,f.created_at
FROM ingestion_file f JOIN ingestion_batch b ON b.batch_id=f.batch_id WHERE b.created_by=?
) u`
		unionArgs = append(unionArgs, q.ActorID)
	}

	whereSQL := strings.Join(where, " AND ")
	baseArgs := append(append([]any{}, unionArgs...), filterArgs...)
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM `+union+` WHERE `+whereSQL, baseArgs...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("admin count tasks: %w", err)
	}
	queryArgs := append(append([]any{}, baseArgs...), pageSize, (page-1)*pageSize)
	rows, err := s.db.QueryContext(ctx, `SELECT task_id,source,batch_id,file_id,version_id,logical_name,task_type,status,progress_stage,attempt,error_code,error_message,started_at,finished_at,created_at FROM `+union+` WHERE `+whereSQL+` ORDER BY created_at DESC,task_id DESC LIMIT ? OFFSET ?`, queryArgs...)
	if err != nil {
		return nil, 0, fmt.Errorf("admin list tasks page: %w", err)
	}
	defer rows.Close()
	var out []CompileTaskView
	for rows.Next() {
		var item CompileTaskView
		var started, finished sql.NullTime
		if err := rows.Scan(&item.TaskID, &item.Source, &item.BatchID, &item.FileID, &item.VersionID, &item.LogicalName, &item.TaskType, &item.Status, &item.ProgressStage, &item.Attempt, &item.ErrorCode, &item.ErrorMessage, &started, &finished, &item.CreatedAt); err != nil {
			return nil, 0, err
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
		out = append(out, item)
	}
	return out, total, rows.Err()
}

func (s *MySQLStore) Facets(ctx context.Context) (FacetsView, error) {
	var out FacetsView
	queries := []struct {
		sql    string
		target *[]string
	}{
		{`SELECT DISTINCT company FROM document_metadata WHERE company<>'' ORDER BY company LIMIT 500`, &out.Companies},
		{`SELECT DISTINCT source_department FROM document_metadata WHERE source_department<>'' ORDER BY source_department LIMIT 500`, &out.SourceDepartments},
		{`SELECT DISTINCT COALESCE(manual_document_type_override,auto_document_type,'') AS value FROM document_metadata WHERE COALESCE(manual_document_type_override,auto_document_type,'')<>'' ORDER BY value LIMIT 500`, &out.DocumentTypes},
		{`SELECT DISTINCT material_category FROM document_metadata WHERE material_category<>'' ORDER BY material_category LIMIT 500`, &out.MaterialCategories},
	}
	for _, query := range queries {
		rows, err := s.db.QueryContext(ctx, query.sql)
		if err != nil {
			return FacetsView{}, err
		}
		for rows.Next() {
			var value string
			if err := rows.Scan(&value); err != nil {
				rows.Close()
				return FacetsView{}, err
			}
			*query.target = append(*query.target, value)
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return FacetsView{}, err
		}
		rows.Close()
	}
	return out, nil
}

func normalizeAdminPage(page, pageSize int) (int, int) {
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
