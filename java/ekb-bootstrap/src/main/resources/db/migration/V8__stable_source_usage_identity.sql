-- One document contributes once to one user's answer, even if the ZIP includes several versions.
-- All downloaded versions remain in the package manifest. Do not rewrite existing usage facts.
CREATE UNIQUE INDEX source_usage_document_identity
ON source_usage_contribution(user_id, session_id, document_id);
