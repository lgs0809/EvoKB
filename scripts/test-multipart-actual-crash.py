#!/usr/bin/env python3
"""Native fixed600 merge recovery and actual partial TCP PUT interruption, no models."""
import argparse
import hashlib
import json
import os
import subprocess
import uuid
from pathlib import Path
from postgres_component_probe import ROOT, run

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--jar-path', type=Path, required=True)
a = p.parse_args()
settings = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                if line and not line.startswith('#'))
os.environ.update(JAVA_TOOL_OPTIONS='-Xmx128m -XX:ActiveProcessorCount=2',
                  PROBE_MINIO_USER=settings['EKB_MINIO_ROOT_USER'],
                  PROBE_MINIO_PASSWORD=settings['EKB_MINIO_ROOT_PASSWORD'])
try:
    code = run(a.output, source_name='MultipartActualCrashProbe.java',
               prefix='multipart_crash_', jar_path=a.jar_path,
               runtime_args=('native_crash_' + uuid.uuid4().hex[:12],), timeout=690)
except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
    partial = json.loads(a.output.read_text()) if a.output.exists() else {}
    partial.update(status='FAIL_NATIVE_MULTIPART_PROBE_HARNESS',
                   failure=type(error).__name__ + ': ' + str(error),
                   jarSha256=hashlib.sha256(a.jar_path.read_bytes()).hexdigest(),
                   probeSourceSha256=hashlib.sha256((ROOT / 'scripts/MultipartActualCrashProbe.java').read_bytes()).hexdigest(),
                   boundary='Actual compile/runtime failure retained; any partial fixtures do not certify completed recovery.')
    a.output.write_text(json.dumps(partial, ensure_ascii=False, indent=2))
    raise
raise SystemExit(code)
