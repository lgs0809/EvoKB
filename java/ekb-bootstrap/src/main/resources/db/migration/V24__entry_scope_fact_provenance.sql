-- Identity/scope support has a distinct purpose from the facts auditing an entry description.
-- Historical rows remain explicitly unproved here; normal compilation supplies new proofs.
ALTER TABLE kb_entry_source ADD COLUMN scope_fact_ids JSONB NOT NULL DEFAULT '[]'::jsonb
  CHECK(jsonb_typeof(scope_fact_ids)='array');
