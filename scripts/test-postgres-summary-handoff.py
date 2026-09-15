"""Retained native PG Summary checkpoint -> failed downstream -> same-task retry recovery."""
import argparse
from pathlib import Path
from postgres_component_probe import run
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar-path',type=Path,required=True)
parser.add_argument('--classes-path',type=Path)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
raise SystemExit(run(args.output,source_name='SummaryHandoffPostgresProbe.java',prefix='evokb_handoff_',jar_path=args.jar_path,classes_path=args.classes_path,timeout=60))
