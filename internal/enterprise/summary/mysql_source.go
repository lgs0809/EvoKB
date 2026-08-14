package summary

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
	enterpriseschema "github.com/xoai/sage-wiki/internal/enterprise/schema"

	_ "github.com/go-sql-driver/mysql"
)

type MySQLStore struct {
	db *sql.DB
}

func OpenMySQL(ctx context.Context, dsn string) (*MySQLStore, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, fmt.Errorf("summary mysql open: %w", err)
	}
	db.SetMaxOpenConns(12)
	db.SetMaxIdleConns(4)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("summary mysql ping: %w", err)
	}
	if err := enterpriseschema.ApplyMySQLDB(ctx, db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("summary mysql migrate: %w", err)
	}
	return &MySQLStore{db: db}, nil
}

func (s *MySQLStore) Close() error { return s.db.Close() }

func (s *MySQLStore) LoadActiveSource(ctx context.Context, versionID string) (SourceVersion, error) {
	var src SourceVersion
	var department, category, tagsJSON, company, autoType, manualType sql.NullString
	var documentDate sql.NullTime
	err := s.db.QueryRowContext(ctx, `
SELECT v.document_id,v.version_id,d.logical_name,
       m.source_department,m.material_category,m.manual_tags,m.company,m.document_date,
       m.auto_document_type,m.manual_document_type_override
FROM document_version v
JOIN document d ON d.document_id=v.document_id
LEFT JOIN document_metadata m ON m.version_id=v.version_id
WHERE v.version_id=? AND v.lifecycle='ACTIVE' AND d.status='ACTIVE'`, versionID).Scan(
		&src.DocumentID, &src.VersionID, &src.LogicalName,
		&department, &category, &tagsJSON, &company, &documentDate, &autoType, &manualType,
	)
	if err != nil {
		if err == sql.ErrNoRows {
			return SourceVersion{}, fmt.Errorf("summary source: active version %s not found", versionID)
		}
		return SourceVersion{}, fmt.Errorf("summary source metadata: %w", err)
	}
	src.AutoDocumentType = autoType.String
	src.ManualDocumentTypeOverride = manualType.String
	src.Metadata.SourceDepartment = department.String
	src.Metadata.MaterialCategory = category.String
	src.Metadata.Company = company.String
	src.Metadata.DocumentType = autoType.String
	if manualType.Valid && manualType.String != "" {
		src.Metadata.DocumentType = manualType.String
	}
	if documentDate.Valid {
		t := documentDate.Time.UTC()
		src.Metadata.DocumentDate = &t
	}
	if tagsJSON.Valid && tagsJSON.String != "" {
		_ = json.Unmarshal([]byte(tagsJSON.String), &src.Metadata.ManualTags)
	}
	blocks, err := s.loadBlocks(ctx, versionID)
	if err != nil {
		return SourceVersion{}, err
	}
	src.Blocks = blocks
	evidence, err := s.loadEvidence(ctx, versionID)
	if err != nil {
		return SourceVersion{}, err
	}
	src.Evidence = evidence
	return src, nil
}

func (s *MySQLStore) loadBlocks(ctx context.Context, versionID string) ([]raw.RawBlock, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT block_id,version_id,block_no,block_type,raw_content,COALESCE(normalized_content,''),
       heading_path,page_start,page_end,bbox_json,source_block_ids,content_hash,COALESCE(token_count,0)
FROM raw_block WHERE version_id=? ORDER BY block_no`, versionID)
	if err != nil {
		return nil, fmt.Errorf("summary load raw blocks: %w", err)
	}
	defer rows.Close()
	var out []raw.RawBlock
	for rows.Next() {
		var b raw.RawBlock
		var headings, bbox, sourceIDs []byte
		var pageStart, pageEnd sql.NullInt64
		if err := rows.Scan(&b.ID, &b.VersionID, &b.BlockNo, &b.BlockType, &b.Content, &b.Normalized,
			&headings, &pageStart, &pageEnd, &bbox, &sourceIDs, &b.ContentHash, &b.TokenCount); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(headings, &b.HeadingPath)
		_ = json.Unmarshal(bbox, &b.BBox)
		_ = json.Unmarshal(sourceIDs, &b.SourceBlockIDs)
		if pageStart.Valid {
			v := int(pageStart.Int64)
			b.PageStart = &v
		}
		if pageEnd.Valid {
			v := int(pageEnd.Int64)
			b.PageEnd = &v
		}
		out = append(out, b)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func (s *MySQLStore) loadEvidence(ctx context.Context, versionID string) ([]raw.Evidence, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT e.evidence_id,e.document_id,e.version_id,e.block_id,e.object_key,e.raw_location,e.content_hash,b.raw_content
FROM evidence e JOIN raw_block b ON b.block_id=e.block_id AND b.version_id=e.version_id
WHERE e.version_id=? AND e.active=TRUE ORDER BY b.block_no`, versionID)
	if err != nil {
		return nil, fmt.Errorf("summary load evidence: %w", err)
	}
	defer rows.Close()
	var out []raw.Evidence
	for rows.Next() {
		var e raw.Evidence
		var location []byte
		if err := rows.Scan(&e.ID, &e.DocumentID, &e.VersionID, &e.BlockID, &e.ObjectKey, &location, &e.Hash, &e.Content); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(location, &e.Location)
		out = append(out, e)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}
