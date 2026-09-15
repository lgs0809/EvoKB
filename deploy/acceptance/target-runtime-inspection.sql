-- Read-only runtime verification. Component probe schemas are separate from public workflow data.
SELECT installed_rank,version,description,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT batch_id,status,source_count,started_at,finished_at FROM ingestion_batch ORDER BY created_at;
SELECT file_id,batch_id,logical_name,status,progress_stage,attempt,document_id,version_id,error_code,error_message
FROM ingestion_file ORDER BY created_at,file_id;
SELECT d.document_id,d.logical_name,v.version_id,v.version_no,v.lifecycle,v.sha256,v.parser_name,v.parser_version,
  m.source_department,m.security_level,m.acl_version,
  (SELECT count(*) FROM raw_block b WHERE b.version_id=v.version_id) raw_blocks,
  (SELECT count(*) FROM evidence e WHERE e.version_id=v.version_id AND e.active) active_evidence
FROM document d JOIN document_version v ON v.document_id=d.document_id
JOIN document_metadata m ON m.version_id=v.version_id ORDER BY d.logical_name,v.version_no;
SELECT session_id,user_id,original_question,status,final_answer FROM query_session ORDER BY created_at;
SELECT session_id,round_no,input_queries,decision FROM retrieval_round ORDER BY session_id,round_no;
SELECT task_id,status,attempt,error_code,error_message FROM compile_task ORDER BY created_at;
