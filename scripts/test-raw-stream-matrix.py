#!/usr/bin/env python3
"""Current immutable artifact/native Redis/PG with actual crashes and a full600 wait."""
import argparse
import os
from pathlib import Path
from postgres_component_probe import run

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--jar-path', type=Path, required=True)
a = p.parse_args()
# Constrain both the small native javac and launched JVMs; run() separately caps Java256MiB.
os.environ['JAVA_TOOL_OPTIONS'] = '-Xmx128m -XX:ActiveProcessorCount=2'
raise SystemExit(run(a.output, source_name='RawStreamMatrixProbe.java', prefix='raw_stream_matrix_', jar_path=a.jar_path, timeout=675))
