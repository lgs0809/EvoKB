"""Freeze a component or deployed artifact for new read-only real-model protocol fixtures."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar-path', type=Path, required=True)
parser.add_argument('--classes-path', type=Path)
parser.add_argument('--fixture', type=Path, default=ROOT/'deploy/acceptance/20261102/grounding-protocol-gold.json')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if any(args.output.with_suffix(suffix).exists() for suffix in ['.json','.compile.log','.runtime.log']):
    raise ValueError('Use a fresh output path; preserve previous responses/failures')
args.output.parent.mkdir(parents=True, exist_ok=True)
settings = dict(line.split('=', 1) for line in (ROOT/'.env.target').read_text().splitlines() if line and not line.startswith('#'))
jar = args.jar_path.read_bytes()
source = (ROOT/'scripts/RealGroundingProtocolProbe.java').read_bytes()
fixture = args.fixture.read_bytes()
args.output.with_suffix('.probe.java').write_bytes(source)
args.output.with_suffix('.gold.json').write_bytes(fixture)
java = Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'], text=True).strip())/'bin'
overlay = {}
with tempfile.TemporaryDirectory(prefix='evokb-real-protocol-') as temp:
    work = Path(temp)
    with zipfile.ZipFile(args.jar_path) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (work/Path(name).name).write_bytes(archive.read(name))
    if args.classes_path:
        for path in args.classes_path.rglob('*.class'):
            data = path.read_bytes(); relative = path.relative_to(args.classes_path)
            dest = work/'overlay'/relative; dest.parent.mkdir(parents=True,exist_ok=True); dest.write_bytes(data)
            overlay[str(relative)] = hashlib.sha256(data).hexdigest()
    src = work/'RealGroundingProtocolProbe.java'; src.write_bytes(source)
    gold = work/'gold.json'; gold.write_bytes(fixture)
    cp = str(work/'overlay')+os.pathsep+str(work/'*')+os.pathsep+str(work)
    compiled = subprocess.run([str(java/'javac'),'-J-Xmx128m','-cp',cp,'-d',str(work),str(src)],capture_output=True,text=True)
    args.output.with_suffix('.compile.log').write_text(compiled.stdout+compiled.stderr); compiled.check_returncode()
    env = dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL=settings['EKB_JDBC_URL'])
    for key in ['EKB_CHAT_PROXY_HOST','EKB_CHAT_PROXY_PORT']:
        if key in settings: env[key] = settings[key]
    executed = subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2',
        '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]', '-cp',cp,'RealGroundingProtocolProbe',str(args.output.resolve()),str(gold)],
        env=env,capture_output=True,timeout=600)
    args.output.with_suffix('.runtime.log').write_bytes(executed.stdout+executed.stderr)
report = json.loads(args.output.read_text())
report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(),jarSha256=hashlib.sha256(jar).hexdigest(),
    fixtureSha256=hashlib.sha256(fixture).hexdigest(),probeSourceSha256=hashlib.sha256(source).hexdigest(),
    compiledClassOverlay=overlay,componentOnly=bool(overlay),deploymentClaim=False)
args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(status=report['status'],cases=sum(len(b.get('results',[])) for b in report['batches']),
    passed=sum(bool(c.get('pass')) for b in report['batches'] for c in b.get('results',[]))),ensure_ascii=False))
raise SystemExit(executed.returncode)
