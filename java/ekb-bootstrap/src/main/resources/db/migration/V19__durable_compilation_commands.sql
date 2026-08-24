-- Preserve every explicit compilation argument and actor; never run admin models in HTTP threads.
CREATE TABLE kb_compilation_command (
  task_id VARCHAR(36) PRIMARY KEY REFERENCES compile_task(task_id),
  knowledge_base_id VARCHAR(128) NOT NULL,
  submitted_by VARCHAR(36) NOT NULL REFERENCES enterprise_user(user_id),
  request_json JSONB NOT NULL,
  input_versions JSONB NOT NULL CHECK(jsonb_typeof(input_versions)='array' AND jsonb_array_length(input_versions)>0),
  request_sha256 VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX kb_compilation_command_actor ON kb_compilation_command(knowledge_base_id,submitted_by,created_at);
