#!/usr/bin/env python3
"""Check atomic Wiki keyword publication through actual PostgreSQL components."""
import argparse
from pathlib import Path
from postgres_component_probe import run

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='WikiDatabaseProjectionProbe.java',
                         prefix='evokb_wiki_commit_', jar_path=args.jar))
