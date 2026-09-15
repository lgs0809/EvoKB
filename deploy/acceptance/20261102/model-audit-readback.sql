-- Local operator inspection of this batch's synthetic-source model receipts only.
-- Application audit reads additionally enforce all original-attempt source permissions.
\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;

SELECT d.logical_name, t.task_id, t.task_type, t.status, t.attempt, t.execution_epoch,
       t.stage_deadline, t.error_code
FROM compile_task t JOIN document_version v USING(version_id) JOIN document d USING(document_id)
WHERE d.logical_name IN ('青禾设备采购合同','青禾设备采购补充协议','青禾设备验收说明')
ORDER BY t.created_at;

SELECT a.event_id, a.target_id AS task_id, a.action, a.outcome, a.created_at,
       a.details_json->>'attempt' AS attempt,
       a.details_json->>'executionEpoch' AS original_execution_epoch,
       a.details_json->>'inputSourceCount' AS captured_source_count,
       a.details_json->>'modelTask' AS model_task,
       a.details_json->>'outputType' AS output_type,
       a.details_json->>'responseBytes' AS full_response_bytes,
       a.details_json->>'responseTruncated' AS response_truncated,
       a.details_json->>'responseSha256' AS full_response_sha256,
       a.details_json->>'failureType' AS transport_failure_type,
       a.details_json->>'responseText' AS bounded_response_prefix
FROM audit_event a JOIN compile_task t ON t.task_id=a.target_id
JOIN document_version v ON v.version_id=t.version_id JOIN document d USING(document_id)
WHERE a.target_type='compile_task'
  AND a.action IN ('COMPILATION_MODEL_OUTPUT','COMPILATION_MODEL_FAILURE')
  AND d.logical_name IN ('青禾设备采购合同','青禾设备采购补充协议','青禾设备验收说明')
ORDER BY a.created_at;

SELECT d.logical_name,s.summary_id,s.lifecycle,s.grounding_status,s.coverage_status,
       f.fact_id,f.fact_text,f.grounding_audit->>'policy_version' AS semantic_policy,
       f.grounding_audit->>'claim_binding' AS claim_binding,
       jsonb_array_length(f.grounding_audit->'checks') AS constraint_count
FROM summary s JOIN summary_fact f USING(summary_id)
JOIN document_version v USING(version_id) JOIN document d USING(document_id)
WHERE d.logical_name IN ('青禾设备采购合同','青禾设备采购补充协议','青禾设备验收说明')
ORDER BY s.created_at,f.created_at;

COMMIT;
