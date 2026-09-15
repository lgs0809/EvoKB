-- Read-only. Retain actual task failures and compatibility facts; no fake publication writes.
SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT task_id,version_id,task_type,status,attempt,error_code,error_message,created_at,started_at,finished_at
FROM compile_task WHERE task_type='GRAPH' ORDER BY created_at;
SELECT e.canonical_entity_id,e.canonical_name,e.entity_type,e.attributes_json
FROM canonical_entity e ORDER BY e.canonical_entity_id;
SELECT r.relation_id,s.canonical_name AS source,t.canonical_name AS target,r.relation_type,r.lifecycle,
  (SELECT count(*) FROM relation_evidence_support p WHERE p.relation_id=r.relation_id AND p.active) AS support_count,
  EXISTS(SELECT 1 FROM kb_graph_relation published WHERE published.relation_id=r.relation_id AND published.is_active) AS has_published_revision
FROM relation_fact r JOIN canonical_entity s ON s.canonical_entity_id=r.source_entity_id
JOIN canonical_entity t ON t.canonical_entity_id=r.target_entity_id ORDER BY r.relation_id;
SELECT kb_id,entity_id,entity_type,canonical_name,scope_key,attributes FROM kb_graph_entity ORDER BY kb_id,entity_id;
SELECT r.kb_id,r.relation_id,r.revision_id,r.source_entity_id,r.target_entity_id,r.relation_type,r.is_active,
  p.evidence_id,e.document_id,e.version_id,p.acl_version,a.acl_version AS current_acl,a.security_level
FROM kb_graph_relation r JOIN kb_graph_evidence p USING(kb_id,relation_id,revision_id)
JOIN evidence e USING(evidence_id) LEFT JOIN kb_source_authority a ON a.version_id=e.version_id
ORDER BY r.kb_id,r.relation_id,r.revision_id,p.evidence_id;
