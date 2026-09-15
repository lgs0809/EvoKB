-- Read-only inspection of real target query sessions and durable reservations.
-- Unfinished reservations still consume budget; they are not successful retrieval rounds.
BEGIN READ ONLY;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT s.session_id, s.user_id, s.status, s.created_at, s.updated_at,
       COUNT(q.reservation_id) AS consumed_slots,
       COALESCE(MAX(q.round_no),0) AS highest_reserved_round,
       COALESCE(SUM(jsonb_array_length(q.input_queries)),0) AS reserved_subqueries,
       COUNT(r.round_id) AS completed_rounds
FROM query_session s
LEFT JOIN query_retrieval_reservation q ON q.session_id=s.session_id
LEFT JOIN retrieval_round r ON r.round_id=q.reservation_id
WHERE s.deleted_at IS NULL
GROUP BY s.session_id ORDER BY s.created_at DESC;
SELECT q.session_id,q.round_no,q.reservation_id,q.input_queries,q.started_at,
       r.decision,r.missing_information,r.next_queries,r.finished_at,
       CASE WHEN r.round_id IS NULL THEN 'CONSUMED_UNFINISHED' ELSE 'COMPLETED' END AS state
FROM query_retrieval_reservation q
LEFT JOIN retrieval_round r ON r.round_id=q.reservation_id
ORDER BY q.started_at DESC,q.round_no;
SELECT q.session_id,q.first_seen_round,q.evidence_id,q.supporting_query,
       e.version_id,a.security_level,a.acl_version
FROM query_evidence q JOIN evidence e ON e.evidence_id=q.evidence_id
LEFT JOIN kb_source_authority a ON a.version_id=e.version_id
ORDER BY q.session_id,q.first_seen_round,q.evidence_id;
COMMIT;
