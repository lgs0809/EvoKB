-- Calculation reuse is not source identity, authorization, or publication.
-- The cache contains hashes and vectors only; all source/evidence checks still run on a hit.
CREATE TABLE raw_vector_cache (
    cache_key VARCHAR(64) PRIMARY KEY,
    profile_hash VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    vector_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    CHECK (profile_hash ~ '^[0-9a-f]{64}$'),
    CHECK (input_hash ~ '^[0-9a-f]{64}$')
);
