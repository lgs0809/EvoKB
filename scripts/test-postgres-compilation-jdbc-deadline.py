"""Real source-row lock cancellation through native Spring transactions; no inference."""
import argparse
from pathlib import Path
from postgres_component_probe import run

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--jar-path', type=Path, required=True)
args = parser.parse_args()
raise SystemExit(run(args.output, source_name='CompilationJdbcDeadlinePostgresProbe.java',
                     prefix='evokb_jdbc_budget_', jar_path=args.jar_path, timeout=30))
