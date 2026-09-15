-- Read-only inspection for the documents uploaded through the real browser.
-- The fixture files in this directory are synthetic; do not manufacture workflow success.
\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;

SELECT d.logical_name, d.document_id, v.version_id, v.version_no, v.lifecycle,
       v.parser_name, v.parser_version, v.sha256
FROM document d JOIN document_version v USING(document_id)
WHERE d.logical_name IN ('青禾设备采购合同', '青禾设备采购补充协议', '青禾设备验收说明')
ORDER BY d.logical_name, v.version_no;

SELECT file_id, batch_id, logical_name, status, progress_stage, attempt,
       execution_epoch, version_id, error_code, started_at, finished_at
FROM ingestion_file
WHERE logical_name IN ('青禾设备采购合同', '青禾设备采购补充协议', '青禾设备验收说明')
ORDER BY created_at;

SELECT s.session_id, s.status, s.object_key, s.document_version_id
FROM kb_upload_session s
WHERE s.document_version_id IN (
  SELECT v.version_id FROM document_version v JOIN document d USING(document_id)
  WHERE d.logical_name IN ('青禾设备采购合同', '青禾设备采购补充协议', '青禾设备验收说明'));

SELECT p.* FROM raw_publication p JOIN document d USING(document_id)
WHERE d.logical_name IN ('青禾设备采购合同', '青禾设备采购补充协议', '青禾设备验收说明');

SELECT t.task_id, t.version_id, t.task_type, t.status, t.attempt, t.execution_epoch,
       t.stage_deadline, t.error_code, t.next_attempt_at, t.started_at, t.finished_at
FROM compile_task t JOIN document_version v USING(version_id) JOIN document d USING(document_id)
WHERE d.logical_name IN ('青禾设备采购合同', '青禾设备采购补充协议', '青禾设备验收说明')
ORDER BY t.created_at;

SELECT f.* FROM compile_task_failure f JOIN compile_task t USING(task_id)
JOIN document_version v ON v.version_id=t.version_id JOIN document d USING(document_id)
WHERE d.logical_name IN ('青禾设备采购合同', '青禾设备采购补充协议', '青禾设备验收说明')
ORDER BY f.occurred_at;

SELECT s.summary_id, s.version_id, s.lifecycle, f.fact_id, f.fact_text,
       f.grounding_status, f.grounding_audit
FROM summary s JOIN summary_fact f USING(summary_id)
JOIN document_version v USING(version_id) JOIN document d USING(document_id)
WHERE d.logical_name IN ('青禾设备采购合同', '青禾设备采购补充协议', '青禾设备验收说明');
COMMIT;
