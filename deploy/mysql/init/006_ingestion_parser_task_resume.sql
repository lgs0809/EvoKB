ALTER TABLE ingestion_file
  ADD COLUMN parser_task_id VARCHAR(128) CHARACTER SET ascii NULL AFTER version_id;
