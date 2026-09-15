#!/usr/bin/env python3
"""Native retained-PG recovery of cold sources after grounded entries appear."""
import argparse
from pathlib import Path
from postgres_component_probe import run

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar-path', type=Path)
    parser.add_argument('--classes-path', type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='LateSourceAssociationProbe.java',
                         prefix='evokb_late_source_', jar_path=args.jar_path,
                         classes_path=args.classes_path))

if __name__ == '__main__':
    main()
