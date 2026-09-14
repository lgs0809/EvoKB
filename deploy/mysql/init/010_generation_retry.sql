-- Additive, data-preserving support for durable background retries.
SET @ekb_retry_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compile_task' AND column_name='next_attempt_at') = 0,
  'ALTER TABLE compile_task ADD COLUMN next_attempt_at TIMESTAMP(6) NULL AFTER finished_at',
  'SELECT 1');
PREPARE ekb_retry_stmt FROM @ekb_retry_ddl;
EXECUTE ekb_retry_stmt;
DEALLOCATE PREPARE ekb_retry_stmt;
ALTER TABLE compile_task MODIFY COLUMN status ENUM('PENDING','RETRY_WAIT','RUNNING','SUCCEEDED','FAILED','CANCELLED') NOT NULL DEFAULT 'PENDING';
CREATE TABLE IF NOT EXISTS compile_task_failure (
  task_id CHAR(36) CHARACTER SET ascii NOT NULL,
  attempt INT UNSIGNED NOT NULL,
  version_id CHAR(36) CHARACTER SET ascii NOT NULL,
  task_type VARCHAR(64) NOT NULL,
  subject_id VARCHAR(255) CHARACTER SET ascii NULL,
  outcome ENUM('RETRY_WAIT','FAILED') NOT NULL,
  error_code VARCHAR(128) NOT NULL,
  error_message TEXT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  next_attempt_at TIMESTAMP(6) NULL,
  PRIMARY KEY (task_id, attempt),
  CONSTRAINT fk_compile_failure_task FOREIGN KEY (task_id) REFERENCES compile_task(task_id)
) ENGINE=InnoDB;
-- Copy recorded terminal failure facts only; no task is retried or marked successful.
INSERT IGNORE INTO compile_task_failure
  (task_id,attempt,version_id,task_type,subject_id,outcome,error_code,error_message,occurred_at,next_attempt_at)
SELECT task_id,attempt,version_id,task_type,subject_id,'FAILED',
       COALESCE(error_code,'GENERATION_FAILED'),error_message,finished_at,NULL
FROM compile_task WHERE status='FAILED' AND attempt>0 AND finished_at IS NOT NULL;
