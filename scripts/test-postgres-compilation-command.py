#!/usr/bin/env python3
"""Durable admin command admission and exact actor/input publication boundaries."""
import argparse
from pathlib import Path
from postgres_component_probe import run

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--jar-path',type=Path)
    p.add_argument('--classes-path',type=Path)
    p.add_argument('--source-migrations',action='store_true')
    a=p.parse_args()
    raise SystemExit(run(a.output,source_name='CompilationCommandProbe.java',prefix='evokb_commands_component_',
        jar_path=a.jar_path,classes_path=a.classes_path,source_migrations=a.source_migrations,
        extra_sql="""
INSERT INTO enterprise_user(user_id,username,password_hash,display_name,role,security_clearance,status)
VALUES('command-admin','command-component-admin','synthetic-not-a-login-credential','Synthetic component admin','ADMIN',30,'ACTIVE'),
      ('command-user','command-component-user','synthetic-not-a-login-credential','Synthetic component user','USER',30,'ACTIVE');
INSERT INTO document_unit(unit_id,version_id,unit_no,structure_type) VALUES('unit-public','version-public',1,'single'),('unit-other','version-other',1,'single');
INSERT INTO summary(summary_id,unit_id,version_id,object_key,content_hash,grounding_status,coverage_status,lifecycle,model)
VALUES('summary-public','unit-public','version-public','component-only','synthetic','PASS','PASS','ACTIVE','SYNTHETIC_COMPONENT_NO_MODEL'),
      ('summary-other','unit-other','version-other','component-only','synthetic','PASS','PASS','ACTIVE','SYNTHETIC_COMPONENT_NO_MODEL');
INSERT INTO wiki(wiki_id,wiki_version,wiki_type,title,object_key,content_hash,lifecycle)
VALUES('synthetic-wiki',1,'CONTRACT','Synthetic component Wiki','component-only','synthetic','ACTIVE');
INSERT INTO kb_asset_build_dependency(asset_type,asset_id,build_revision,source_version_id,acl_version)
VALUES('WIKI','synthetic-wiki','1','version-public',2),('WIKI','synthetic-wiki','1','version-other',1);
"""))
