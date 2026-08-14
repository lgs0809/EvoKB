package ingest

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Worker struct {
	Store        *Store
	Engine       *raw.Engine
	PollInterval time.Duration
}

func (w *Worker) Run(ctx context.Context) error {
	if w == nil || w.Store == nil || w.Engine == nil {
		return fmt.Errorf("ingestion worker dependencies are required")
	}
	if err := w.Store.ResetInterrupted(ctx); err != nil {
		return fmt.Errorf("ingestion worker recover: %w", err)
	}
	interval := w.PollInterval
	if interval <= 0 {
		interval = time.Second
	}
	for {
		if err := ctx.Err(); err != nil {
			return nil
		}
		file, err := w.Store.ClaimNext(ctx)
		if err != nil {
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(interval):
				continue
			}
		}
		if file == nil {
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(interval):
				continue
			}
		}
		w.process(ctx, *file)
	}
}

func (w *Worker) process(ctx context.Context, file File) {
	if _, err := os.Stat(file.StagingPath); err != nil {
		_ = w.Store.Fail(ctx, file.FileID, "STAGING_FILE_MISSING", err.Error())
		return
	}
	progress := func(stage string) {
		_ = w.Store.UpdateProgress(ctx, file.FileID, stage)
	}
	result, err := w.Engine.IngestPath(ctx, raw.IngestRequest{
		Path:        file.StagingPath,
		LogicalName: file.LogicalName,
		SourcePath:  "web-batch:" + file.RelativePath,
		Metadata: raw.Metadata{
			SourceDepartment: file.SourceDepartment,
			MaterialCategory: file.MaterialCategory,
			ManualTags:       append([]string(nil), file.ManualTags...),
			Company:          file.Company,
			DocumentDate:     file.DocumentDate,
			DocumentType:     file.DocumentType,
		},
		Progress: progress,
	})
	if err != nil {
		_ = w.Store.Fail(ctx, file.FileID, "INGEST_FAILED", err.Error())
		return
	}
	if err := w.Store.Complete(ctx, file.FileID, result.DocumentID, result.VersionID); err != nil {
		_ = w.Store.Fail(ctx, file.FileID, "TASK_PERSIST_FAILED", err.Error())
		return
	}
	_ = os.Remove(file.StagingPath)
}
