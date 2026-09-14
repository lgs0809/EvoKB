ALTER TABLE compile_task
  ADD COLUMN subject_id VARCHAR(255) CHARACTER SET ascii NULL AFTER task_type;

CREATE INDEX idx_compile_version_type_status
  ON compile_task(version_id, task_type, status);
