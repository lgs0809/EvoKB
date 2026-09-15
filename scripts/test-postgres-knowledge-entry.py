#!/usr/bin/env python3
"""Retained real PostgreSQL stable-entry/receipt-driven scheduling component checks."""
import argparse
from pathlib import Path
from postgres_component_probe import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--classes-path', type=Path)
    parser.add_argument('--jar-path', type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='KnowledgeEntryProbe.java',
                         prefix='evokb_entry_component_',
                         extra_sql="""
INSERT INTO enterprise_user(user_id,username,password_hash,display_name,role,security_clearance,status)
VALUES('component-usage','entry-component-fixture','synthetic-not-a-login-credential','Synthetic component','USER',10,'ACTIVE');
""", classes_path=args.classes_path, jar_path=args.jar_path))


if __name__ == '__main__':
    main()
