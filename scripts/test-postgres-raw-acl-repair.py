#!/usr/bin/env python3
"""Native PostgreSQL repair admission, deduplication and exact Raw publication scope."""
import argparse
from pathlib import Path
from postgres_component_probe import run

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar-path', type=Path)
    parser.add_argument('--classes-path', type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='RawAclRepairProbe.java',
        prefix='evokb_raw_acl_repair_', jar_path=args.jar_path, classes_path=args.classes_path))
