-- Attempt input snapshots are independent of final evidence-supported catalogue membership.
CREATE TABLE compile_task_input (
  task_id VARCHAR(36) NOT NULL REFERENCES compile_task(task_id),
  execution_epoch BIGINT NOT NULL CHECK(execution_epoch>0),
  source_version_id VARCHAR(36) NOT NULL REFERENCES document_version(version_id),
  acl_version BIGINT NOT NULL CHECK(acl_version>0),
  PRIMARY KEY(task_id,execution_epoch,source_version_id)
);
ALTER TABLE kb_derived_asset DROP CONSTRAINT kb_derived_asset_stage_check;
ALTER TABLE kb_derived_asset ADD CONSTRAINT kb_derived_asset_stage_check CHECK(stage IN ('SUMMARY','CONCEPT','WIKI'));
ALTER TABLE kb_derived_asset ADD COLUMN publication_state VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
  CHECK(publication_state IN ('STAGED','PUBLISHED'));
CREATE TABLE kb_asset_current (
  asset_id VARCHAR(255) PRIMARY KEY,
  asset_version BIGINT NOT NULL,
  published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(asset_id,asset_version) REFERENCES kb_derived_asset(asset_id,asset_version)
);
-- Existing immutable revisions remain intact, with an explicit current projection identity.
INSERT INTO kb_asset_current(asset_id,asset_version)
SELECT asset_id,MAX(asset_version) FROM kb_derived_asset GROUP BY asset_id;
