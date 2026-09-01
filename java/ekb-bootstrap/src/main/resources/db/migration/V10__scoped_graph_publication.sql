-- Existing graph revisions retain their original provenance. Do not manufacture a
-- business object identity/scope for historical rows that did not record one.
ALTER TABLE kb_graph_entity ADD COLUMN object_id VARCHAR(255);
CREATE UNIQUE INDEX uq_graph_object_identity ON kb_graph_entity(kb_id,entity_type,object_id)
  WHERE object_id IS NOT NULL;
ALTER TABLE kb_graph_relation ADD COLUMN scope_key VARCHAR(255);
ALTER TABLE kb_graph_relation ADD COLUMN scope_json JSONB NOT NULL DEFAULT '{}';
ALTER TABLE kb_graph_relation ADD COLUMN confidence DOUBLE PRECISION
  CHECK(confidence>=0 AND confidence<=1);
CREATE INDEX idx_graph_relation_business_scope
  ON kb_graph_relation(kb_id,source_entity_id,target_entity_id,relation_type,scope_key)
  WHERE is_active;
