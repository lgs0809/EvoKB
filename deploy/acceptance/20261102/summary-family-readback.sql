-- Read-only: retained real task family, normal successful independent note, and historical overlimit leaf.
-- Do not use these queries to reset attempts, change source ACL, or manufacture a successful state.
SELECT task_id,version_id,task_type,subject_id,status,attempt,source_acl_version,execution_epoch,
       created_at,started_at,finished_at,error_code,idempotency_key
FROM compile_task
WHERE version_id IN ('75782b74-0580-49af-8ea5-a4e3bad22102',
                     'df9174e1-c39f-44f5-876d-e19a17049f8d')
ORDER BY created_at,task_id;
SELECT task_id,attempt,error_code,occurred_at
FROM compile_task_failure
WHERE task_id IN ('5c9895d2-c7a2-485a-9025-6343e5f967a4',
                  'bcc67152-5443-4d47-9af6-5cbd21a56b65',
                  '93a34a70-0329-4e4f-9798-ce976b529076')
ORDER BY task_id,attempt;
SELECT s.summary_id,s.version_id,s.lifecycle,s.grounding_status,s.coverage_status,s.content_hash,
       s.object_key,a.asset_version,a.publication_state,
       (SELECT count(*) FROM summary_fact f WHERE f.summary_id=s.summary_id) AS retained_facts,
       (SELECT count(*) FROM concept_candidate c WHERE c.summary_id=s.summary_id) AS concepts,
       (SELECT count(*) FROM kb_entry_build_input b WHERE b.summary_id=s.summary_id) AS entry_inputs
FROM summary s LEFT JOIN kb_derived_asset a ON a.asset_id=s.summary_id
WHERE s.summary_id IN ('72bf762a-9d18-40f3-9b6f-8e89c33328d0',
                       '2a38beaa-7daa-4fb4-955a-db26c4c35e45')
ORDER BY s.summary_id,a.asset_version;
-- This is receipt metadata only. Actual model output remains subject to original-input ACL in the API.
SELECT target_id,action,created_at,details_json->>'executionEpoch' AS original_epoch,
       details_json->>'model' AS model,details_json->>'task' AS model_task,
       details_json->>'responseSha256' AS response_sha256,
       details_json->>'responseTruncated' AS response_truncated
FROM audit_event
WHERE target_type='compile_task'
  AND target_id IN ('bcc67152-5443-4d47-9af6-5cbd21a56b65',
                    'c89daa55-d755-4803-93d6-c75b23ec6177',
                    '93a34a70-0329-4e4f-9798-ce976b529076')
ORDER BY created_at,event_id;
