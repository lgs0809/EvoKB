-- Preserve new constraint checks as part of the existing atomic publication.
-- Historical PASS rows are retained without pretending they used the new audit policy.
ALTER TABLE summary_fact ADD COLUMN grounding_reason TEXT NOT NULL DEFAULT '';
ALTER TABLE wiki_claim ADD COLUMN grounding_reason TEXT NOT NULL DEFAULT '';
ALTER TABLE summary_fact ADD COLUMN grounding_audit JSONB;
ALTER TABLE wiki_claim ADD COLUMN grounding_audit JSONB;
