#!/usr/bin/env python3
'Run the real compiled PostgreSQL component in a fresh retained synthetic schema.'
import argparse
from pathlib import Path
from postgres_component_probe import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='PostgresGraphProbe.java', prefix='evokb_graph_probe_'))


if __name__ == "__main__":
    main()
