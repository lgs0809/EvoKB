-- Durable per-knowledge-base single writer, bounded by the same database stage deadline.
ALTER TABLE compile_task ADD COLUMN knowledge_base_id VARCHAR(255) NOT NULL DEFAULT 'enterprise';
CREATE INDEX compile_task_kb_queue ON compile_task(knowledge_base_id,status,created_at,task_id);
CREATE TABLE kb_compilation_control (
  knowledge_base_id VARCHAR(255) PRIMARY KEY,
  control_epoch BIGINT NOT NULL DEFAULT 0 CHECK(control_epoch>=0),
  task_id VARCHAR(36) REFERENCES compile_task(task_id),
  task_execution_epoch BIGINT,
  owner_id VARCHAR(36),
  stage_deadline TIMESTAMPTZ,
  CHECK ((task_id IS NULL AND task_execution_epoch IS NULL AND owner_id IS NULL AND stage_deadline IS NULL)
      OR (task_id IS NOT NULL AND task_execution_epoch>0 AND owner_id IS NOT NULL AND stage_deadline IS NOT NULL))
);
