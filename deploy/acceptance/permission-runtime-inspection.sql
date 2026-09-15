-- Read-only target inspection. Do not run against a different database.
BEGIN READ ONLY;
SELECT current_database(), current_schema(), CURRENT_TIMESTAMP AS observed_at;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;

-- Only explicitly marked synthetic accounts; never output password/session hashes.
SELECT user_id, username, role, status, security_clearance
FROM enterprise_user WHERE username LIKE 'acceptance_permission_%' ORDER BY username;

SELECT d.logical_name,v.version_id,v.lifecycle,v.sha256,m.security_level,m.acl_version,a.security_level AS effective_security_level
FROM document d JOIN document_version v ON v.document_id=d.document_id
JOIN document_metadata m ON m.version_id=v.version_id
LEFT JOIN kb_source_authority a ON a.version_id=v.version_id
WHERE d.logical_name LIKE 'HT-ACL-031%' OR d.logical_name LIKE 'HT-MV-017%'
ORDER BY d.logical_name,v.version_no;

-- Actual browser queries retain their original answer; API views revalidate current access.
SELECT q.session_id,q.status,q.evidence_only,q.created_at,q.updated_at,md5(q.final_answer) AS saved_answer_fingerprint,
       q.citation_evidence_ids,
       (SELECT count(*) FROM retrieval_round r WHERE r.session_id=q.session_id) AS actual_rounds,
       (SELECT count(*) FROM query_source_dependency dep WHERE dep.session_id=q.session_id) AS actual_input_versions
FROM query_session q JOIN enterprise_user u ON u.user_id=q.user_id
WHERE u.username='acceptance_permission_confidential' ORDER BY q.created_at;

SELECT dep.session_id,dep.version_id,d.logical_name,a.security_level
FROM query_source_dependency dep JOIN query_session q ON q.session_id=dep.session_id
JOIN enterprise_user u ON u.user_id=q.user_id
JOIN document_version v ON v.version_id=dep.version_id JOIN document d ON d.document_id=v.document_id
LEFT JOIN kb_source_authority a ON a.version_id=dep.version_id
WHERE u.username='acceptance_permission_confidential' ORDER BY dep.session_id,d.logical_name;

SELECT p.package_id,u.username,p.evidence_ids,p.created_at,p.expires_at,p.deleted_at
FROM source_package_access p JOIN enterprise_user u ON u.user_id=p.user_id
WHERE u.username LIKE 'acceptance_permission_%' ORDER BY p.created_at;

SELECT m.session_id,u.username,m.created_at,m.expires_at
FROM mcp_session_access m JOIN enterprise_user u ON u.user_id=m.user_id
WHERE u.username LIKE 'acceptance_permission_%' ORDER BY m.created_at;

-- These are real public workflow rows; isolated component probe schemas are separate.
SELECT f.file_id,f.logical_name,f.status,f.progress_stage,f.version_id,f.attempt,f.error_code
FROM ingestion_file f WHERE f.batch_id='aad75309-8d57-4cd6-89b1-76ff1be8f345' ORDER BY f.logical_name;
COMMIT;
