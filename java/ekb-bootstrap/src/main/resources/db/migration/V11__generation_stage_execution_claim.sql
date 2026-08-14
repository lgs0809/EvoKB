-- Fixed database-clock 600-second stages, without heartbeat renewal.
-- Preserve existing task rows and failure history. Historical RUNNING stages get
-- a conservative deadline; a second process starting cannot invalidate them.
ALTER TABLE compile_task ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE compile_task ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0 CHECK(execution_epoch >= 0);
ALTER TABLE compile_task ADD COLUMN stage_deadline TIMESTAMPTZ;
ALTER TABLE compile_task ADD COLUMN source_acl_version BIGINT CHECK(source_acl_version > 0);

UPDATE compile_task t
SET owner_id='legacy-before-v11', execution_epoch=GREATEST(t.attempt,1),
    stage_deadline=COALESCE(t.started_at,clock_timestamp()) + INTERVAL '600 seconds',
    source_acl_version=(SELECT m.acl_version FROM document_metadata m WHERE m.version_id=t.version_id)
WHERE t.status='RUNNING';

CREATE INDEX compile_task_expired_stage ON compile_task(task_id,stage_deadline)
WHERE status='RUNNING';
