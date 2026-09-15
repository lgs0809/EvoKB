#!/usr/bin/env python3
"""Execute frozen20dev/10holdout reliability cases with actual local native components."""
import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path
from postgres_component_probe import ROOT, run

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--jar-path', type=Path, required=True)
p.add_argument('--manifest', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
manifest = json.loads(a.manifest.read_text())
if manifest['jarSha256'] != hashlib.sha256(a.jar_path.read_bytes()).hexdigest():
    raise SystemExit('Immutable execution artifact must match frozen case manifest')
for case in manifest['cases']:
    if hashlib.sha256(Path(case['originalPath']).read_bytes()).hexdigest() != case['originalSha256']:
        raise SystemExit('Frozen original changed: ' + case['caseId'])
settings = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                if line and not line.startswith('#'))
os.environ.update(JAVA_TOOL_OPTIONS='-Xmx128m -XX:ActiveProcessorCount=2',
                  PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],
                  PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
try:
    code = run(a.output, source_name='RawStreamThirtyProbe.java', prefix='raw_stream30_',
               jar_path=a.jar_path, runtime_args=(str(a.manifest.resolve()),),
               namespace_arg=True, timeout=900)
except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
    partial = json.loads(a.output.read_text()) if a.output.exists() else {}
    partial.update(status='FAIL_STREAM30_NATIVE_HARNESS',
                   failure=type(error).__name__ + ': ' + str(error),
                   jarSha256=manifest['jarSha256'],
                   manifestSha256=hashlib.sha256(a.manifest.read_bytes()).hexdigest(),
                   probeSourceSha256=hashlib.sha256((ROOT / 'scripts/RawStreamThirtyProbe.java').read_bytes()).hexdigest(),
                   boundary='Actual compile/runtime harness failure retained; unexecuted cases remain untested. No business/model success inferred from partial initialization.')
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(partial, ensure_ascii=False, indent=2))
    raise
raise SystemExit(code)
