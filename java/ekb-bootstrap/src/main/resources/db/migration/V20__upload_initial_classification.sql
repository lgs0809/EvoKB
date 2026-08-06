-- Nullable means no explicit classification was requested: new sources use 10; successors
-- inherit the existing document classification. This is not an authority or publication field.
ALTER TABLE ingestion_file ADD COLUMN requested_security_level SMALLINT
  CHECK(requested_security_level IS NULL OR requested_security_level BETWEEN 10 AND 127);
