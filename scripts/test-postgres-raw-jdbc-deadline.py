"""Native row-lock cancellation on current artifact Raw transactions; no model or business writes."""
import argparse
from pathlib import Path
from postgres_component_probe import run

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--jar-path', type=Path, required=True)
a = p.parse_args()
raise SystemExit(run(a.output, source_name='RawJdbcDeadlinePostgresProbe.java',
                     prefix='evokb_raw_jdbc_', jar_path=a.jar_path, timeout=30))
