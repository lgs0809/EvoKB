"""Retained PostgreSQL V21 upgrade and actual atomic audit repository roundtrip."""
import argparse
import hashlib
import json
from pathlib import Path
from postgres_component_probe import run

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--jar-path', type=Path, required=True, help='Immutable pre-V21 verified artifact')
parser.add_argument('--classes-path', type=Path, required=True, help='Retained candidate classes from focused JUnit freeze')
args = parser.parse_args()
migration = (ROOT/'java/ekb-bootstrap/src/main/resources/db/migration/V21__claim_grounding_audit.sql').read_bytes()
input_migration = (ROOT/'java/ekb-bootstrap/src/main/resources/db/migration/V22__complete_publication_inputs.sql').read_bytes()
scope_digest = hashlib.sha256(b'business_scope\0CONTRACT\0synthetic fixture source').hexdigest()[:32]
scope_key = '-'.join((scope_digest[:8],scope_digest[8:12],scope_digest[12:16],scope_digest[16:20],scope_digest[20:]))
seed = """
INSERT INTO compile_task(task_id,version_id,task_type,subject_id,idempotency_key,status,attempt)
VALUES('legacy-generation-task','version-public','SUMMARY','synthetic-legacy-policy','generation:summary:version-public:synthetic-legacy-policy','SUCCEEDED',1);
INSERT INTO document_unit(unit_id,version_id,unit_no,structure_type)
VALUES('unit-public','version-public',1,'single'),('unit-legacy','version-public',2,'single'),('unit-other','version-other',1,'single'),('unit-secret','version-secret',1,'single');
INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model)
VALUES('legacy-summary','unit-legacy','version-public','component-only','synthetic','PASS','PASS','ACTIVE','SYNTHETIC_PRE_AUDIT');
INSERT INTO summary_fact(fact_id,summary_id,section,fact_text,grounding_status)
VALUES('legacy-fact','legacy-summary','source','Synthetic fixture source','PASS');
INSERT INTO summary_evidence(fact_id,evidence_id) VALUES('legacy-fact','evidence-public');
INSERT INTO wiki(wiki_id,wiki_version,wiki_type,title,object_key,content_hash,lifecycle)
VALUES('legacy-wiki',1,'CONTRACT','Synthetic pre-audit Wiki','component-only','synthetic','ACTIVE');
INSERT INTO wiki_claim(claim_id,wiki_id,wiki_version,section,claim_text,grounding_status,temporal_status)
VALUES('legacy-claim','legacy-wiki',1,'source','Synthetic fixture source','PASS','PASS');
INSERT INTO wiki_evidence(claim_id,evidence_id) VALUES('legacy-claim','evidence-public');
INSERT INTO kb_asset_build_dependency(asset_type,asset_id,build_revision,source_version_id,acl_version)
VALUES('WIKI','legacy-wiki','1','version-public',1);
INSERT INTO compile_task(task_id,version_id,task_type,subject_id,idempotency_key,status,attempt,knowledge_base_id)
VALUES('legacy-input-policy-wiki-task','version-public','WIKI','entry/native-inputs-entry/revision/1',
       'LEGACY_INPUT_POLICY_KEY','SUCCEEDED',1,'complete-inputs-component');
"""
legacy_input_kb = 'complete-inputs-component'
legacy_input_key = 'generation:wiki:grounding-constraints-v1:version-public:entry/native-inputs-entry/revision/1'
seed = seed.replace('LEGACY_INPUT_POLICY_KEY', hashlib.sha256(
    f'{len(legacy_input_kb)}:{legacy_input_kb}:{legacy_input_key}'.encode()).hexdigest())
# Independent pre-upgrade historical entry. No public task/Gold/history is altered.
seed += """
INSERT INTO concept_candidate(concept_id,summary_id,version_id,preferred_label,concept_type_hint,description,lifecycle,model)
VALUES('legacy-grounding-concept','legacy-summary','version-public','Synthetic fixture','CONTRACT','LEGACY_UNREVIEWED_PROSE_SHOULD_NEVER_ENTER_MODEL','ACTIVE','SYNTHETIC_PRE_AUDIT');
INSERT INTO concept_fact_support(concept_id,fact_id) VALUES('legacy-grounding-concept','legacy-fact');
INSERT INTO kb_knowledge_entry(knowledge_base_id,entry_id,revision,scope_key,scope_json,entry_type,preferred_label,aliases,description,stage,lifecycle)
VALUES('grounding-component','legacy-grounding-entry',1,'SCOPE_KEY_PLACEHOLDER','{"identity_status":"SCOPE_GROUNDED","anchors":[{"type":"CONTRACT","value":"synthetic fixture source"}]}','CONTRACT','Synthetic fixture','["Synthetic","Unrelated stale alias"]','LEGACY_UNREVIEWED_PROSE_SHOULD_NEVER_ENTER_MODEL','CONCEPT','ACTIVE');
INSERT INTO kb_entry_source(knowledge_base_id,entry_id,document_id,source_version_id,summary_id,concept_id,acl_version,fact_ids)
VALUES('grounding-component','legacy-grounding-entry','doc-public','version-public','legacy-summary','legacy-grounding-concept',1,'["legacy-fact"]');
INSERT INTO kb_entry_build_input(knowledge_base_id,entry_id,entry_revision,summary_id,source_version_id,acl_version)
VALUES('grounding-component','legacy-grounding-entry',1,'legacy-summary','version-public',1);
""".replace('SCOPE_KEY_PLACEHOLDER',scope_key)
result = run(args.output, source_name='GroundingAuditPostgresProbe.java', prefix='evokb_grounding_audit_',
             jar_path=args.jar_path, classes_path=args.classes_path,
             extra_sql=seed+'\n-- Explicit candidate V21/V22 applied after retained legacy rows.\n'+migration.decode()+'\n'+input_migration.decode())
report = json.loads(args.output.read_text())
report.update(candidateV21Sha256=hashlib.sha256(migration).hexdigest(),
              candidateV22Sha256=hashlib.sha256(input_migration).hexdigest(),
              historicalRowsCreatedBeforeCandidateMigration=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
raise SystemExit(result)
