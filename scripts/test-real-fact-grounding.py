#!/usr/bin/env python3
"""Check the existing synthetic actor inversion using the actual immutable judge client."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--candidate-audit', action='store_true', help='Compile frozen candidate Summary/Wiki/audit classes over immutable release libraries; not a deployed run')
    args = parser.parse_args()
    uuid.UUID(args.version)
    if any(args.output.with_suffix(suffix).exists() for suffix in ('.json','.compile.log','.runtime.log')):
        raise ValueError('Use a fresh report stem and preserve failures')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    state = json.loads((ROOT/'.tmp/target/process.json').read_text())
    artifact = Path(state['artifact'])
    jar = artifact.read_bytes()
    if hashlib.sha256(jar).hexdigest()!=state['jarSha256']:
        raise ValueError('Immutable execution artifact changed')
    settings = dict(line.split('=',1) for line in (ROOT/'.env.target').read_text().splitlines()
                    if line and not line.startswith('#'))
    source = ROOT/'scripts/RealFactGroundingProbe.java'
    frozen_bytes = source.read_bytes()
    args.output.with_suffix('.probe.java').write_bytes(frozen_bytes)
    java = Path(subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip())/'bin'
    with tempfile.TemporaryDirectory(prefix='evokb-real-grounding-') as temp:
        work=Path(temp)
        frozen=work/source.name;frozen.write_bytes(frozen_bytes)
        with zipfile.ZipFile(artifact) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work/Path(name).name).write_bytes(archive.read(name))
        cp=os.pathsep.join((str(work),str(work/'*')))
        overlays=[]
        overlay_hashes={}
        if args.candidate_audit:
            for name in ('GroundingConstraintAudit','GroundingJudgeProtocol','CompilationContracts','SummaryCompilationService','WikiCompilationService'):
                original=ROOT/'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation'/f'{name}.java'
                overlay=work/original.name; overlay.write_bytes(original.read_bytes()); overlays.append(overlay)
                retained=args.output.with_suffix('.candidate-src')/original.name; retained.parent.mkdir(exist_ok=True); retained.write_bytes(overlay.read_bytes())
                overlay_hashes[str(original.relative_to(ROOT))]=hashlib.sha256(overlay.read_bytes()).hexdigest()
        compile_result=subprocess.run([str(java/'javac'),'-J-Xmx128m','-cp',cp,'-d',str(work),str(frozen),*(str(p) for p in overlays)],
                                      capture_output=True,text=True,timeout=60)
        args.output.with_suffix('.compile.log').write_text(compile_result.stdout+compile_result.stderr)
        compile_result.check_returncode()
        env=dict(os.environ,PROBE_DB_PASSWORD=settings['EKB_POSTGRES_PASSWORD'],PROBE_JDBC_URL=settings['EKB_JDBC_URL'])
        # Preserve the actual deployment's configured transport. Do not print its credentials.
        for key in ('EKB_CHAT_PROXY_HOST','EKB_CHAT_PROXY_PORT'):
            if key in settings:env[key]=settings[key]
        result=subprocess.run([str(java/'java'),'-Xmx256m','-XX:ActiveProcessorCount=2',
            '-DsocksNonProxyHosts=localhost|127.*|[::1]|0.0.0.0|[::0]',
            '-cp',cp,'RealFactGroundingProbe',str(args.output.resolve()),args.version,('audited' if args.candidate_audit else 'baseline')],
            env=env,capture_output=True,timeout=900)
        args.output.with_suffix('.runtime.log').write_bytes(result.stdout+result.stderr)
    report=json.loads(args.output.read_text())
    report.update(actualOperationTime=datetime.now(timezone.utc).isoformat(),executionArtifact=state,
                  probeSourceSha256=hashlib.sha256(frozen_bytes).hexdigest(),
                  candidateSourceOverlay=overlay_hashes,
                  candidateDeployed=False if args.candidate_audit else None,
                  configurationReader='READ_ONLY_NATIVE_ACTIVE_PROFILE_NO_BOOTSTRAP_WRITES',
                  finalProcessStillMatchesLaunch=json.loads((ROOT/'.tmp/target/process.json').read_text())==state)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status=report['status'],actualCandidates=len(report['results']),
                         passed=sum(bool(r.get('pass')) for r in report['results']),
                         executionJar=state['jarSha256']),ensure_ascii=False))
    raise SystemExit(result.returncode)


if __name__=='__main__':main()
