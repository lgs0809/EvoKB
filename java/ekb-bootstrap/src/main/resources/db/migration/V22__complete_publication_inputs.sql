-- Never certify historical evidence-only revisions through a migration.
ALTER TABLE wiki ADD COLUMN input_policy_version VARCHAR(64);
ALTER TABLE wiki ADD COLUMN input_source_count INTEGER CHECK(input_source_count>0);
ALTER TABLE kb_graph_relation ADD COLUMN input_policy_version VARCHAR(64);
ALTER TABLE kb_graph_relation ADD COLUMN input_source_count INTEGER CHECK(input_source_count>0);
ALTER TABLE kb_derived_asset ADD COLUMN input_policy_version VARCHAR(64);
ALTER TABLE kb_derived_asset ADD COLUMN input_source_count INTEGER CHECK(input_source_count>0);
CREATE TABLE kb_graph_build_dependency (
  kb_id VARCHAR(128) NOT NULL,
  relation_id VARCHAR(255) NOT NULL,
  revision_id VARCHAR(64) NOT NULL,
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  acl_version BIGINT NOT NULL CHECK(acl_version>0),
  PRIMARY KEY(kb_id,relation_id,revision_id,source_version_id),
  FOREIGN KEY(kb_id,relation_id,revision_id)
    REFERENCES kb_graph_relation(kb_id,relation_id,revision_id)
);
CREATE INDEX kb_graph_build_sources ON kb_graph_build_dependency(source_version_id,kb_id,relation_id);
