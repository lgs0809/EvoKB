"""Real PostgreSQL persistent retry/alias/restart/authority regression, no external model."""
import argparse
from pathlib import Path
from postgres_component_probe import run
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar-path',type=Path,required=True)
parser.add_argument('--classes-path',type=Path)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
raise SystemExit(run(args.output,source_name='DurableRetryPostgresProbe.java',prefix='evokb_retry_',jar_path=args.jar_path,classes_path=args.classes_path,timeout=100))
