#!/usr/bin/env python3
'Run the real compiled PostgreSQL component in a fresh retained synthetic schema.'
import argparse
from pathlib import Path
from postgres_component_probe import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--wait-deadline", action="store_true", help="Actually wait the unchanged 600-second database deadline and normal retry backoff")
    args = parser.parse_args()
    raise SystemExit(run(args.output, source_name='PostgresGenerationStageProbe.java', prefix='evokb_stage_probe_',
                         extra_sql="\nCREATE TABLE publication_probe(marker VARCHAR(128) PRIMARY KEY);\n",
                         runtime_args=(str(args.wait_deadline).lower(),), timeout=900))


if __name__ == "__main__":
    main()
