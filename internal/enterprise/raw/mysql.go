package raw

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"

	_ "github.com/go-sql-driver/mysql"
)

type MySQLStore struct {
	db *sql.DB
}

func OpenMySQL(ctx context.Context, dsn string) (*MySQLStore, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("mysql open: %w", err)
	}
	db.SetMaxOpenConns(20)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("mysql migrate: %w", err)
	}
	return &MySQLStore{db: db}, nil
}

func (s *MySQLStore) Close() error { return s.db.Close() }

func (s *MySQLStore) FindDocumentByLogicalName(ctx context.Context, logicalName string) (*Document, error) {
	var d Document
	err := s.db.QueryRowContext(ctx,
		`SELECT document_id, logical_name, COALESCE(source_path,'') FROM document WHERE logical_name=? AND status='ACTIVE' ORDER BY created_at LIMIT 1`,
		logicalName).Scan(&d.ID, &d.LogicalName, &d.SourcePath)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("mysql find document: %w", err)
	}
	return &d, nil
}

func (s *MySQLStore) FindVersionBySHA(ctx context.Context, documentID, sha256 string) (*DocumentVersion, error) {
	return s.findVersion(ctx, `WHERE document_id=? AND sha256=? ORDER BY version_no DESC LIMIT 1`, documentID, sha256)
}

func (s *MySQLStore) CurrentActiveVersion(ctx context.Context, documentID string) (*DocumentVersion, error) {
	return s.findVersion(ctx, `WHERE document_id=? AND lifecycle='ACTIVE' ORDER BY version_no DESC LIMIT 1`, documentID)
}

func (s *MySQLStore) findVersion(ctx context.Context, suffix string, args ...any) (*DocumentVersion, error) {
	var v DocumentVersion
	err := s.db.QueryRowContext(ctx, `
SELECT version_id,document_id,version_no,lifecycle,original_filename,COALESCE(media_type,''),
       size_bytes,sha256,original_object_key,COALESCE(markdown_object_key,''),
       COALESCE(structured_object_key,''),COALESCE(parser_name,''),COALESCE(parser_version,'')
FROM document_version `+suffix, args...).Scan(
		&v.ID, &v.DocumentID, &v.VersionNo, &v.Lifecycle, &v.OriginalFilename, &v.MediaType,
		&v.SizeBytes, &v.SHA256, &v.OriginalObjectKey, &v.MarkdownObjectKey,
		&v.StructuredKey, &v.ParserName, &v.ParserVersion,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("mysql find version: %w", err)
	}
	return &v, nil
}

func (s *MySQLStore) NextVersionNo(ctx context.Context, documentID string) (uint64, error) {
	var n uint64
	if err := s.db.QueryRowContext(ctx,
		`SELECT COALESCE(MAX(version_no),0)+1 FROM document_version WHERE document_id=?`, documentID).Scan(&n); err != nil {
		return 0, fmt.Errorf("mysql next version: %w", err)
	}
	return n, nil
}

func (s *MySQLStore) CreatePendingVersion(ctx context.Context, doc Document, version DocumentVersion, md Metadata) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `
INSERT INTO document(document_id, logical_name, source_path, status)
VALUES(?,?,?,'ACTIVE')
ON DUPLICATE KEY UPDATE logical_name=VALUES(logical_name), source_path=VALUES(source_path), status='ACTIVE'`,
		doc.ID, doc.LogicalName, nullable(doc.SourcePath)); err != nil {
		return fmt.Errorf("mysql upsert document: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO document_version(
  version_id, document_id, version_no, lifecycle, original_filename, media_type,
  size_bytes, sha256, original_object_key)
VALUES(?,?,?,'PENDING',?,?,?,?,?)`,
		version.ID, version.DocumentID, version.VersionNo, version.OriginalFilename,
		nullable(version.MediaType), version.SizeBytes, version.SHA256, version.OriginalObjectKey); err != nil {
		return fmt.Errorf("mysql insert version: %w", err)
	}
	tags, _ := json.Marshal(md.ManualTags)
	var docDate any
	if md.DocumentDate != nil {
		docDate = md.DocumentDate.UTC().Format("2006-01-02")
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO document_metadata(version_id,source_department,material_category,manual_tags,company,document_date,auto_document_type)
VALUES(?,?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE source_department=VALUES(source_department),material_category=VALUES(material_category),manual_tags=VALUES(manual_tags),company=VALUES(company),document_date=VALUES(document_date),auto_document_type=VALUES(auto_document_type)`,
		version.ID, nullable(md.SourceDepartment), nullable(md.MaterialCategory), string(tags), nullable(md.Company), docDate, nullable(md.DocumentType)); err != nil {
		return fmt.Errorf("mysql metadata: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("mysql create pending commit: %w", err)
	}
	return nil
}

func (s *MySQLStore) SaveParsed(ctx context.Context, versionID, markdownKey, structuredKey, parserName, parserVersion string, blocks []RawBlock, evidence []Evidence) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM evidence WHERE version_id=?`, versionID); err != nil {
		return fmt.Errorf("mysql clear evidence: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM raw_block WHERE version_id=?`, versionID); err != nil {
		return fmt.Errorf("mysql clear raw blocks: %w", err)
	}
	stmt, err := tx.PrepareContext(ctx, `
INSERT INTO raw_block(block_id,version_id,block_no,block_type,raw_content,normalized_content,heading_path,page_start,page_end,bbox_json,source_block_ids,content_hash,token_count)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	for _, b := range blocks {
		headings, _ := json.Marshal(b.HeadingPath)
		bbox, _ := json.Marshal(b.BBox)
		sources, _ := json.Marshal(b.SourceBlockIDs)
		if _, err := stmt.ExecContext(ctx, b.ID, b.VersionID, b.BlockNo, b.BlockType, b.Content, b.Normalized,
			string(headings), ptrValue(b.PageStart), ptrValue(b.PageEnd), string(bbox), string(sources), b.ContentHash, b.TokenCount); err != nil {
			_ = stmt.Close()
			return fmt.Errorf("mysql insert raw block %s: %w", b.ID, err)
		}
	}
	_ = stmt.Close()
	estmt, err := tx.PrepareContext(ctx, `
INSERT INTO evidence(evidence_id,document_id,version_id,block_id,object_key,raw_location,content_hash,active)
VALUES(?,?,?,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	for _, e := range evidence {
		loc, _ := json.Marshal(e.Location)
		if _, err := estmt.ExecContext(ctx, e.ID, e.DocumentID, e.VersionID, e.BlockID, e.ObjectKey, string(loc), e.Hash, false); err != nil {
			_ = estmt.Close()
			return fmt.Errorf("mysql insert evidence %s: %w", e.ID, err)
		}
	}
	_ = estmt.Close()
	if _, err := tx.ExecContext(ctx, `
UPDATE document_version
SET markdown_object_key=?, structured_object_key=?, parser_name=?, parser_version=?, parsed_at=CURRENT_TIMESTAMP(6), failure_code=NULL, failure_message=NULL
WHERE version_id=?`, markdownKey, structuredKey, parserName, parserVersion, versionID); err != nil {
		return fmt.Errorf("mysql update parsed version: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("mysql save parsed commit: %w", err)
	}
	return nil
}

func (s *MySQLStore) ActivateVersion(ctx context.Context, documentID, versionID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `
UPDATE summary s JOIN document_version v ON v.version_id=s.version_id
SET s.lifecycle='STALE'
WHERE v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE' AND s.lifecycle='ACTIVE'`, documentID, versionID); err != nil {
		return fmt.Errorf("mysql stale old summaries: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE relation_evidence_support rs
JOIN evidence e ON e.evidence_id=rs.evidence_id
JOIN document_version v ON v.version_id=e.version_id
SET rs.active=FALSE
WHERE v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE' AND rs.active=TRUE`, documentID, versionID); err != nil {
		return fmt.Errorf("mysql deactivate old relation supports: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
UPDATE wiki w
JOIN wiki_claim c ON c.wiki_id=w.wiki_id AND c.wiki_version=w.wiki_version
JOIN wiki_evidence we ON we.claim_id=c.claim_id
JOIN evidence e ON e.evidence_id=we.evidence_id
JOIN document_version v ON v.version_id=e.version_id
SET w.lifecycle='STALE'
WHERE v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE' AND w.lifecycle='ACTIVE'`, documentID, versionID); err != nil {
		return fmt.Errorf("mysql stale old evidence-backed wikis: %w", err)
	}
	// Evidence support is a file-version lifecycle concern. Business relation
	// valid_from/valid_to fields are intentionally untouched here.
	if _, err := tx.ExecContext(ctx, `
UPDATE evidence e JOIN document_version v ON v.version_id=e.version_id
SET e.active=FALSE
WHERE v.document_id=? AND v.version_id<>? AND v.lifecycle='ACTIVE'`, documentID, versionID); err != nil {
		return fmt.Errorf("mysql deactivate old evidence: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `UPDATE document_version SET lifecycle='INACTIVE' WHERE document_id=? AND version_id<>? AND lifecycle='ACTIVE'`, documentID, versionID); err != nil {
		return fmt.Errorf("mysql deactivate old versions: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `UPDATE document_version SET lifecycle='ACTIVE', activated_at=CURRENT_TIMESTAMP(6) WHERE document_id=? AND version_id=?`, documentID, versionID); err != nil {
		return fmt.Errorf("mysql activate version: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `UPDATE evidence SET active=TRUE WHERE version_id=?`, versionID); err != nil {
		return fmt.Errorf("mysql activate evidence: %w", err)
	}
	return tx.Commit()
}

func (s *MySQLStore) MarkVersionFailed(ctx context.Context, versionID, code, message string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `UPDATE document_version SET lifecycle='FAILED', failure_code=?, failure_message=? WHERE version_id=?`, code, message, versionID); err != nil {
		return fmt.Errorf("mysql mark failed: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `UPDATE evidence SET active=? WHERE version_id=?`, false, versionID); err != nil {
		return fmt.Errorf("mysql deactivate failed evidence: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("mysql mark failed commit: %w", err)
	}
	return nil
}

func (s *MySQLStore) ResolveEvidence(ctx context.Context, evidenceIDs []string) ([]Evidence, error) {
	if len(evidenceIDs) == 0 {
		return nil, nil
	}
	q := `SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location,e.content_hash,b.raw_content
FROM evidence e JOIN raw_block b ON b.block_id=e.block_id
WHERE e.active=TRUE AND e.evidence_id IN (` + placeholders(len(evidenceIDs)) + `)`
	args := make([]any, len(evidenceIDs))
	for i, id := range evidenceIDs {
		args[i] = id
	}
	rows, err := s.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, fmt.Errorf("mysql resolve evidence: %w", err)
	}
	defer rows.Close()
	byID := make(map[string]Evidence, len(evidenceIDs))
	for rows.Next() {
		var e Evidence
		var loc []byte
		if err := rows.Scan(&e.ID, &e.DocumentID, &e.VersionID, &e.BlockID, &e.ObjectKey, &loc, &e.Hash, &e.Content); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(loc, &e.Location)
		byID[e.ID] = e
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]Evidence, 0, len(byID))
	for _, id := range evidenceIDs {
		if e, ok := byID[id]; ok {
			out = append(out, e)
		}
	}
	return out, nil
}

func nullable(s string) any {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	return s
}

func ptrValue(v *int) any {
	if v == nil {
		return nil
	}
	return *v
}

func placeholders(n int) string {
	if n <= 0 {
		return ""
	}
	return strings.TrimRight(strings.Repeat("?,", n), ",")
}
