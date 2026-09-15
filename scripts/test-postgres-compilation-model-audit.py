"""Native retained-schema model-output audit storage and original-attempt source authorization."""
import argparse
from pathlib import Path
from postgres_component_probe import run

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--jar-path', type=Path, required=True)
parser.add_argument('--classes-path', type=Path)
args = parser.parse_args()
raise SystemExit(run(args.output, source_name='CompilationModelAuditPostgresProbe.java',
                     prefix='evokb_model_audit_', jar_path=args.jar_path,
                     classes_path=args.classes_path, timeout=45))
