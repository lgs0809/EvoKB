#!/usr/bin/env python3
"""Actual PostgreSQL selective-generation quotas, usage identity and transaction races."""
import argparse
from pathlib import Path
from postgres_component_probe import run

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar-path', type=Path)
    parser.add_argument('--classes-path', type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='SelectiveGenerationProbe.java',
        prefix='evokb_selection_component_', jar_path=args.jar_path, classes_path=args.classes_path,
        extra_sql="""
INSERT INTO enterprise_user(user_id,username,password_hash,display_name,role,security_clearance,status)
VALUES('quota-user','quota-component-fixture','synthetic-not-a-login-credential','Synthetic component','USER',10,'ACTIVE');
"""))
