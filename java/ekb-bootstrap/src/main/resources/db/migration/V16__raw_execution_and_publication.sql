-- Raw ownership: DB clock, fixed per-stage 600 seconds, no heartbeats/renewal.
ALTER TABLE ingestion_file ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ingestion_file ADD COLUMN owner_id VARCHAR(36);
ALTER TABLE ingestion_file ADD COLUMN stage_started_at TIMESTAMPTZ;
ALTER TABLE ingestion_file ADD COLUMN execution_stage VARCHAR(64);
ALTER TABLE ingestion_file ADD COLUMN stage_deadline TIMESTAMPTZ;
ALTER TABLE ingestion_file ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE ingestion_file ADD COLUMN isolated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ingestion_file ADD COLUMN claimed_acl_version BIGINT;
CREATE INDEX ingestion_due_recovery ON ingestion_file(status,next_attempt_at,stage_deadline,file_id);
-- Unknown pre-migration in-flight attempts receive a conservative initial deadline, not an
-- immediate startup reset. Future stages are always anchored by DB clock at actual entry.
UPDATE ingestion_file SET execution_epoch=GREATEST(execution_epoch,1),
 owner_id='00000000-0000-0000-0000-000000000000',execution_stage=progress_stage,
 stage_started_at=clock_timestamp(),stage_deadline=clock_timestamp()+interval '600 seconds'
 WHERE status='RUNNING' AND stage_deadline IS NULL;

CREATE TABLE raw_ingestion_outbox (
 file_id VARCHAR(36) PRIMARY KEY REFERENCES ingestion_file(file_id),
 delivery_epoch BIGINT NOT NULL DEFAULT 1,
 message_id VARCHAR(128),
 dispatched_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE raw_ingestion_failure (
 file_id VARCHAR(36) NOT NULL REFERENCES ingestion_file(file_id),
 execution_epoch BIGINT NOT NULL,
 attempt INT NOT NULL,
 stage VARCHAR(64) NOT NULL,
 outcome VARCHAR(32) NOT NULL CHECK(outcome IN ('RETRY_WAIT','ISOLATED','INTERRUPTED')),
 error_code VARCHAR(128) NOT NULL,
 error_message TEXT,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 next_attempt_at TIMESTAMPTZ,
 PRIMARY KEY(file_id,execution_epoch)
);
CREATE TABLE raw_ingestion_delivery_history (
 message_id VARCHAR(128) PRIMARY KEY,
 file_id VARCHAR(36) NOT NULL REFERENCES ingestion_file(file_id),
 delivery_epoch BIGINT NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE raw_ingestion_delivery_failure (
 message_id VARCHAR(128) PRIMARY KEY,
 file_reference VARCHAR(128),
 error_code VARCHAR(128) NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE raw_publication (
 version_id VARCHAR(36) PRIMARY KEY REFERENCES document_version(version_id),
 document_id VARCHAR(36) NOT NULL REFERENCES document(document_id),
 file_id VARCHAR(36) REFERENCES ingestion_file(file_id),
 compile_task_id VARCHAR(36) REFERENCES compile_task(task_id),
 producer_scope VARCHAR(255) NOT NULL,
 execution_epoch BIGINT NOT NULL,
 acl_version BIGINT NOT NULL,
 published_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((file_id IS NOT NULL AND compile_task_id IS NULL) OR (file_id IS NULL AND compile_task_id IS NOT NULL))
);
CREATE TABLE raw_publication_member (
 version_id VARCHAR(36) NOT NULL REFERENCES raw_publication(version_id),
 physical_id VARCHAR(255) NOT NULL,
 PRIMARY KEY(version_id,physical_id)
);
-- Existing queued jobs are recovered without altering existing documents/vectors/data volumes.
INSERT INTO raw_ingestion_outbox(file_id) SELECT file_id FROM ingestion_file WHERE status='QUEUED';
