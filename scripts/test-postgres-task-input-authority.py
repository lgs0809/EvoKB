"""Native task metadata regression; old artifact counterexample and candidate proof stay separate."""
import argparse
from pathlib import Path
from postgres_component_probe import run

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--jar-path', type=Path, required=True)
parser.add_argument('--classes-path', type=Path)
args = parser.parse_args()
raise SystemExit(run(args.output, source_name='TaskInputAccessProbe.java',
                     prefix='evokb_task_inputs_', jar_path=args.jar_path,
                     classes_path=args.classes_path))
