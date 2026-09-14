-- Read-only; safe to rerun against the retained local target.
SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT asset_id,asset_version,stage,created_at FROM kb_derived_asset ORDER BY created_at;
SELECT asset_id,asset_version,document_id,source_version_id,evidence_ids FROM kb_asset_source ORDER BY asset_id,asset_version;
SELECT asset_id,asset_version,source_version_id,acl_version FROM kb_asset_dependency ORDER BY asset_id,asset_version;
SELECT session_id,asset_id,asset_version,source_version_id,evidence_id,first_seen_round FROM query_asset_expansion ORDER BY created_at;
SELECT user_id,session_id,asset_id,asset_version,receipt_id,created_at FROM asset_usage_contribution ORDER BY created_at;
SELECT asset_id,count(*) AS effective_downloads_30d FROM asset_usage_contribution WHERE created_at>=CURRENT_TIMESTAMP-INTERVAL '30 days' GROUP BY asset_id ORDER BY effective_downloads_30d DESC,asset_id;
SELECT task_id,task_type,status,attempt,error_code,error_message FROM compile_task ORDER BY created_at;
