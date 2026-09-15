"""Capture a new genuine Luna response for actual stored synthetic facts; never task acceptance."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
import uuid
from native_probe_runtime import FrozenBootArtifact, compile_java, run_java

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version', required=True)
parser.add_argument('--summary', required=True)
parser.add_argument('--fact-offset', type=int, default=0)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
uuid.UUID(args.version)
uuid.UUID(args.summary)
if args.fact_offset < 0:
    raise ValueError('Nonnegative actual fact offset required')
if args.output.exists() or args.output.with_suffix('.probe.java').exists():
    raise ValueError('Fresh private diagnostic output required')
args.output.parent.mkdir(parents=True, exist_ok=True)
state = json.loads((ROOT/'.tmp/target/process.json').read_text())
artifact = FrozenBootArtifact(state['artifact'], state['jarSha256'])
settings = dict(line.split('=', 1) for line in (ROOT/'.env.target').read_text().splitlines()
                if line and not line.startswith('#'))
source = ROOT/'scripts/RealGroundingBatchDiagnostic.java'
snapshot = args.output.with_suffix('.probe.java')
snapshot.write_bytes(source.read_bytes())
snapshot.chmod(0o600)
env = dict(os.environ, PROBE_JDBC_URL=settings['EKB_JDBC_URL'], PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'])
for key in ('EKB_CHAT_PROXY_HOST', 'EKB_CHAT_PROXY_PORT'):
    if key in settings:
        env[key] = settings[key]
with tempfile.TemporaryDirectory(prefix='evokb-new-grounding-diagnostic-') as temp:
    work = Path(temp)
    cp = artifact.extract(work/'artifact')
    frozen = work/source.name
    frozen.write_bytes(snapshot.read_bytes())
    classes = work/'probe'
    compile_java(cp.value, [frozen], classes, args.output.with_suffix('.compile.log')).check_returncode()
    result = run_java(str(classes)+os.pathsep+cp.value, 'RealGroundingBatchDiagnostic',
                      [args.output.resolve(), args.version, args.summary, args.fact_offset],
                      args.output.with_suffix('.runtime.log'), env=env, timeout=660,
                      extra_jvm_args=['-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]'])
report = json.loads(args.output.read_text())
report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(),
              executionArtifact=state, probeSourceSha256=hashlib.sha256(snapshot.read_bytes()).hexdigest(),
              finalArtifactStillMatches=artifact.unchanged(),
              processIdentityUnchanged=json.loads((ROOT/'.tmp/target/process.json').read_text()) == state,
              original605015MissingResponse='NOT_CAPTURED_NOT_RECONSTRUCTED')
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
args.output.chmod(0o600)
print(json.dumps({k: report.get(k) for k in ['status', 'smallModel', 'modelSeconds', 'configurationUnchanged']}, ensure_ascii=False))
raise SystemExit(result.returncode)
