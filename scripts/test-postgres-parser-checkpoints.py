"""Actual retained pre-V23 schema upgrade, normal reparse and publication fence components."""
import argparse
from pathlib import Path
from postgres_component_probe import run

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--jar-path', type=Path, required=True)
parser.add_argument('--pre-upgrade-jar', type=Path, required=True)
parser.add_argument('--classes-path', type=Path)
parser.add_argument('--source-migrations', action='store_true', help='Explicit frozen candidate migration component overlay; never a deployment claim')
args = parser.parse_args()
old = """
INSERT INTO enterprise_user(user_id,username,display_name,password_hash,role,status,security_clearance) VALUES
('admin','component_admin','Synthetic admin','COMPONENT_NOT_LOGIN','ADMIN','ACTIVE',30),
('low-admin','component_low_admin','Synthetic low admin','COMPONENT_NOT_LOGIN','ADMIN','ACTIVE',10),
('owner','component_owner','Synthetic owner','COMPONENT_NOT_LOGIN','USER','ACTIVE',30),
('other','component_other','Synthetic other','COMPONENT_NOT_LOGIN','USER','ACTIVE',30);
INSERT INTO document(document_id,logical_name,status) VALUES('reparse-doc','Synthetic retained parser original','ACTIVE');
INSERT INTO document_version(version_id,document_id,version_no,lifecycle,original_filename,size_bytes,sha256,original_object_key)
VALUES('legacy-retained','reparse-doc',1,'ACTIVE','original.txt',42,encode(sha256(repeat('X',42)::bytea),'hex'),'synthetic/original');
INSERT INTO document_metadata(version_id,security_level,manual_tags) VALUES('legacy-retained',30,'[]');
INSERT INTO ingestion_batch(batch_id,created_by,created_by_username,status,source_count) VALUES('legacy-batch','owner','component_owner','FAILED',1);
INSERT INTO ingestion_file(file_id,batch_id,relative_path,original_filename,staging_path,size_bytes,sha256,logical_name,status,progress_stage,attempt,document_id,version_id,error_code,error_message)
VALUES('legacy-five','legacy-batch','original.txt','original.txt','/synthetic/legacy-staging',42,encode(sha256(repeat('X',42)::bytea),'hex'),'Synthetic retained parser original','FAILED','PARSING',5,'reparse-doc','legacy-retained','ORIGINAL_FAILURE','Preserved synthetic five-attempt failure');
INSERT INTO raw_publication(version_id,document_id,file_id,producer_scope,execution_epoch,acl_version)
VALUES('legacy-retained','reparse-doc','legacy-five','legacy-five:e4',4,1);
INSERT INTO raw_publication_member(version_id,physical_id) VALUES('legacy-retained','synthetic-old-physical:e'||substr(encode(sha256('legacy-five:e4'::bytea),'hex'),1,24));
"""
raise SystemExit(run(args.output, source_name='ParserCheckpointPostgresProbe.java',
    prefix='evokb_parser_',jar_path=args.jar_path,classes_path=args.classes_path,timeout=90,namespace_arg=True,
    source_migrations=args.source_migrations,pre_upgrade_jar_path=args.pre_upgrade_jar,pre_upgrade_sql=old))
