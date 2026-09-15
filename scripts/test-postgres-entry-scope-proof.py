"""Real PG scope-purpose persistence, V23 upgrade, revoked evidence and API authority."""
import argparse
from pathlib import Path
from postgres_component_probe import run
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--jar-path',type=Path,required=True)
p.add_argument('--pre-upgrade-jar-path',type=Path,required=True)
p.add_argument('--classes-path',type=Path)
p.add_argument('--source-migrations',action='store_true')
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
legacy="""
INSERT INTO document(document_id,logical_name,status) VALUES('legacy-document','Synthetic retained scope fixture','ACTIVE');
INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key) VALUES('legacy-version','legacy-document',1,'ACTIVE','legacy.md',0,'component-only','component-only');
INSERT INTO document_metadata(version_id,security_level) VALUES('legacy-version',10);
INSERT INTO document_unit(unit_id,version_id,unit_no,title) VALUES('legacy-unit','legacy-version',1,'Synthetic legacy scope');
INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model) VALUES('legacy-summary','legacy-unit','legacy-version','component-only','component-only','PASS','PASS','ACTIVE','SYNTHETIC_COMPONENT_NO_MODEL');
INSERT INTO concept_candidate(concept_id,summary_id,version_id,preferred_label,concept_type_hint,description,model) VALUES('legacy-concept','legacy-summary','legacy-version','Legacy scope','CONTRACT','Retained original description','SYNTHETIC_COMPONENT_NO_MODEL');
INSERT INTO kb_knowledge_entry(knowledge_base_id,entry_id,revision,scope_key,scope_json,entry_type,preferred_label,aliases,description,stage,lifecycle)
VALUES('component-kb','legacy-scope-entry',1,'legacy-scope','{"identity_status":"SCOPE_GROUNDED","anchors":[{"type":"CONTRACT","value":"legacy-contract"}]}','CONTRACT','Legacy scope','[]','Retained original description','CONCEPT','ACTIVE');
INSERT INTO kb_entry_source(knowledge_base_id,entry_id,document_id,source_version_id,summary_id,concept_id,acl_version,fact_ids)
VALUES('component-kb','legacy-scope-entry','legacy-document','legacy-version','legacy-summary','legacy-concept',1,'["legacy-description"]');
"""
raise SystemExit(run(a.output,source_name='EntryScopeProofPostgresProbe.java',prefix='evokb_scope_proof_',jar_path=a.jar_path,classes_path=a.classes_path,source_migrations=a.source_migrations,additional_sources=('KnowledgeEntryProbe.java',),pre_upgrade_jar_path=a.pre_upgrade_jar_path,pre_upgrade_sql=legacy))
