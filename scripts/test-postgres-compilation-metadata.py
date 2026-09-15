"""Native Summary metadata must be pinned before guarded remote object/model operations."""
import argparse
from pathlib import Path
from postgres_component_probe import run

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--jar-path', type=Path, required=True)
parser.add_argument('--classes-path', type=Path)
args = parser.parse_args()
raise SystemExit(run(args.output, source_name='CompilationMetadataPostgresProbe.java',
                     prefix='evokb_metadata_', jar_path=args.jar_path,
                     classes_path=args.classes_path, timeout=30))
