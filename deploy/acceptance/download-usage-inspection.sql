-- Read-only manual inspection of actual target downloads, not workflow result manipulation.
BEGIN READ ONLY;
SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT p.package_id,p.user_id,p.expires_at,p.deleted_at,m.size_bytes,
       COUNT(DISTINCT s.version_id) AS sources,COUNT(DISTINCT a.session_id) AS answers,
       MAX(r.completed_at) AS transferred_at
FROM source_package_access p LEFT JOIN package_usage_manifest m USING(package_id)
LEFT JOIN package_manifest_source s USING(package_id)
LEFT JOIN package_answer_source a USING(package_id)
LEFT JOIN package_download_receipt r USING(package_id)
GROUP BY p.package_id,p.user_id,p.expires_at,p.deleted_at,m.size_bytes ORDER BY p.created_at DESC;
SELECT c.user_id,c.session_id,d.logical_name,c.version_id,c.created_at,r.package_id,
       r.transferred_bytes,a.security_level
FROM source_usage_contribution c JOIN package_download_receipt r USING(receipt_id)
JOIN document d ON d.document_id=c.document_id
LEFT JOIN kb_source_authority a ON a.version_id=c.version_id ORDER BY c.created_at DESC;
-- Hotness is current source version only, over a rolling 30-day window. Historical facts remain.
SELECT d.logical_name,v.version_id,v.version_no,a.security_level,COUNT(c.session_id) AS valid_downloads_30d
FROM document d JOIN document_version v USING(document_id)
LEFT JOIN kb_source_authority a USING(version_id)
LEFT JOIN source_usage_contribution c ON c.version_id=v.version_id
AND c.created_at>=clock_timestamp()-INTERVAL '30 days'
GROUP BY d.logical_name,v.version_id,v.version_no,a.security_level ORDER BY d.logical_name,v.version_no;
SELECT search_id,COUNT(*) FILTER(WHERE effective) AS legacy_effective,
       COUNT(*) FILTER(WHERE NOT effective) AS delivery_only FROM search_result GROUP BY search_id;
COMMIT;
