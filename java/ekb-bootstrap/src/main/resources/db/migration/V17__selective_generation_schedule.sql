-- Actual receipt ledgers remain the only heat source. This table records scheduling, not usage.
CREATE TABLE kb_generation_candidate (
  knowledge_base_id VARCHAR(255) NOT NULL,
  stage VARCHAR(16) NOT NULL CHECK(stage IN ('SUMMARY','WIKI','GRAPH')),
  target_id VARCHAR(255) NOT NULL,
  target_revision BIGINT NOT NULL CHECK(target_revision>0),
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  contributions BIGINT NOT NULL CHECK(contributions>=0),
  first_queued_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  qualified_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  selected_task_id VARCHAR(36) REFERENCES compile_task(task_id),
  PRIMARY KEY(knowledge_base_id,stage,target_id,target_revision)
);
CREATE TABLE kb_generation_daily_batch (
  knowledge_base_id VARCHAR(255) NOT NULL,
  business_day DATE NOT NULL,
  completed_at TIMESTAMPTZ,
  PRIMARY KEY(knowledge_base_id,business_day)
);
CREATE TABLE kb_generation_daily_selection (
  knowledge_base_id VARCHAR(255) NOT NULL,
  business_day DATE NOT NULL,
  stage VARCHAR(16) NOT NULL CHECK(stage IN ('SUMMARY','WIKI','GRAPH')),
  target_id VARCHAR(255) NOT NULL,
  target_revision BIGINT NOT NULL,
  task_id VARCHAR(36) NOT NULL REFERENCES compile_task(task_id),
  selected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(knowledge_base_id,stage,target_id,target_revision),
  FOREIGN KEY(knowledge_base_id,business_day) REFERENCES kb_generation_daily_batch(knowledge_base_id,business_day)
);
ALTER TABLE compile_task ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
CREATE INDEX compile_task_repair_priority ON compile_task(knowledge_base_id,status,priority DESC,created_at,task_id);
CREATE TABLE kb_entry_pending_source (
  knowledge_base_id VARCHAR(255) NOT NULL,
  entry_id VARCHAR(36) NOT NULL,
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  evidence_ids JSONB NOT NULL CHECK(jsonb_typeof(evidence_ids)='array' AND jsonb_array_length(evidence_ids)>0),
  impact_text TEXT NOT NULL,
  affected_source_versions JSONB NOT NULL CHECK(jsonb_typeof(affected_source_versions)='array'),
  detected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  associated_at TIMESTAMPTZ,
  graph_repaired_at TIMESTAMPTZ,
  PRIMARY KEY(knowledge_base_id,entry_id,source_version_id),
  FOREIGN KEY(knowledge_base_id,entry_id) REFERENCES kb_knowledge_entry(knowledge_base_id,entry_id)
);
