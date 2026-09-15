#!/usr/bin/env python3
"""Test query expiry and late-completion fencing against the deployed JAR and isolated PostgreSQL."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verification', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output.with_suffix('.compile.log').exists() or output.with_suffix('.run.log').exists():
        raise ValueError('Use a fresh output stem; keep previous evidence')
    state_path = ROOT / '.tmp/target/process.json'
    state = json.loads(state_path.read_text())
    review = json.loads(args.verification.read_text())
    jar = Path(state['artifact'])
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if review.get('status') != 'PASS_FULL_JAVA_VERIFY' or digest != state['jarSha256'] or digest != review.get('jarSha256'):
        raise ValueError('Use the exact fully verified deployed artifact')
    private = dict(line.split('=', 1) for line in (ROOT / '.env.target').read_text().splitlines()
                   if line and not line.startswith('#'))
    if private['EKB_JDBC_URL'].split('?', 1)[0] != 'jdbc:postgresql://127.0.0.1:15465/evokb_target':
        raise ValueError('Only the isolated local target is allowed')
    output.parent.mkdir(parents=True, exist_ok=True)
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()) / 'bin'
    source = ROOT / 'scripts/QueryExpiryNativeProbe.java'
    source_bytes = source.read_bytes()
    with tempfile.TemporaryDirectory(prefix='evokb-query-expiry-native-') as temporary:
        folder = Path(temporary)
        modules = {}
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    raw = archive.read(name)
                    (folder / Path(name).name).write_bytes(raw)
                    if Path(name).name.startswith('ekb-'):
                        modules[Path(name).name] = hashlib.sha256(raw).hexdigest()
        frozen_source = folder / source.name
        frozen_source.write_bytes(source_bytes)
        classpath = os.pathsep.join([str(folder / '*'), str(folder)])
        compiled = subprocess.run([str(java / 'javac'), '-J-Xmx128m', '-cp', classpath,
                                   '-d', str(folder), str(frozen_source)], text=True, capture_output=True)
        output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        env = dict(os.environ, PROBE_JDBC_URL=private['EKB_JDBC_URL'],
                   PROBE_DB_PASSWORD=private['EKB_POSTGRES_PASSWORD'])
        tested = subprocess.run([str(java / 'java'), '-Xmx128m', '-XX:ActiveProcessorCount=2',
                                 '-cp', classpath, 'QueryExpiryNativeProbe', str(output)],
                                env=env, text=True, capture_output=True, timeout=120)
        output.with_suffix('.run.log').write_text(
            (tested.stdout + tested.stderr).replace(private['EKB_POSTGRES_PASSWORD'], '<private>'))
        if output.exists():
            actual = json.loads(output.read_text())
            unchanged = json.loads(state_path.read_text()) == state
            actual.update(process=state, deployedRuntimeUnchanged=unchanged, moduleJars=modules,
                          probeSourceSha256=hashlib.sha256(source_bytes).hexdigest())
            if not unchanged:
                actual['status'] = 'FAIL_RUNTIME_CHANGED_DURING_NATIVE_PROBE'
            output.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps({key: actual[key] for key in ('status', 'schema', 'checks')}, ensure_ascii=False))
            if not unchanged:
                raise SystemExit(1)
        tested.check_returncode()


if __name__ == '__main__':
    main()
