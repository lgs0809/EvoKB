CREATE TABLE kb_graph_entity (
  kb_id VARCHAR(128) NOT NULL,
  entity_id VARCHAR(255) NOT NULL,
  entity_type VARCHAR(64) NOT NULL CHECK (entity_type NOT IN ('CHUNK','SUMMARY','CONCEPT','WIKI')),
  canonical_name VARCHAR(1024) NOT NULL,
  canonical_key TEXT NOT NULL,
  scope_key VARCHAR(255) NOT NULL,
  attributes JSONB NOT NULL DEFAULT '{}',
  PRIMARY KEY(kb_id,entity_id)
);
CREATE INDEX idx_graph_entity_scope ON kb_graph_entity(kb_id,entity_type,scope_key);

CREATE TABLE kb_graph_relation (
  kb_id VARCHAR(128) NOT NULL,
  relation_id VARCHAR(255) NOT NULL,
  revision_id VARCHAR(64) NOT NULL,
  source_entity_id VARCHAR(255) NOT NULL,
  target_entity_id VARCHAR(255) NOT NULL,
  relation_type VARCHAR(128) NOT NULL CHECK (relation_type <> 'COLLECTS_SOURCE'),
  valid_from TIMESTAMP WITH TIME ZONE,
  valid_to TIMESTAMP WITH TIME ZONE,
  conflict_status VARCHAR(32) NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(kb_id,relation_id,revision_id),
  CHECK(source_entity_id <> target_entity_id),
  FOREIGN KEY(kb_id,source_entity_id) REFERENCES kb_graph_entity(kb_id,entity_id),
  FOREIGN KEY(kb_id,target_entity_id) REFERENCES kb_graph_entity(kb_id,entity_id)
);
CREATE UNIQUE INDEX uq_graph_current_relation ON kb_graph_relation(kb_id,relation_id) WHERE is_active;
CREATE INDEX idx_graph_source_frontier ON kb_graph_relation(kb_id,source_entity_id,relation_id) WHERE is_active;
CREATE INDEX idx_graph_target_frontier ON kb_graph_relation(kb_id,target_entity_id,relation_id) WHERE is_active;

CREATE TABLE kb_graph_evidence (
  kb_id VARCHAR(128) NOT NULL,
  relation_id VARCHAR(255) NOT NULL,
  revision_id VARCHAR(64) NOT NULL,
  evidence_id VARCHAR(36) NOT NULL REFERENCES evidence(evidence_id),
  acl_version BIGINT NOT NULL,
  PRIMARY KEY(kb_id,relation_id,revision_id,evidence_id),
  FOREIGN KEY(kb_id,relation_id,revision_id) REFERENCES kb_graph_relation(kb_id,relation_id,revision_id)
);
CREATE INDEX idx_graph_evidence_source ON kb_graph_evidence(evidence_id,kb_id,relation_id,revision_id);
