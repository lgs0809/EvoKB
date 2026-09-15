#!/usr/bin/env python3
"""Verify atomic query/history writes and duplicate clarification races in a retained schema."""
import argparse
import json
from pathlib import Path
from postgres_component_probe import ROOT, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source-classes', type=Path,
                        help='Explicit staged class overlay; records component-only, not deployed proof')
    args = parser.parse_args()
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    return run(args.output, source_name='QueryLifecycleAtomicProbe.java',
               prefix='evokb_query_lifecycle_', namespace_arg=True,
               jar_path=state['artifact'], classes_path=args.source_classes)


if __name__ == '__main__':
    raise SystemExit(main())
