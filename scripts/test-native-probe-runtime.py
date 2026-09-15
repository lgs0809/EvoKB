"""Resource/byte-provenance regression for the shared native harness; zero models."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch
import zipfile

from native_probe_runtime import FrozenBootArtifact, RUNTIME_SOURCE_SHA256, compile_java, java17_bin, run_java


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Fresh evidence required')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    checks = {}
    artifact = FrozenBootArtifact(args.jar)
    with tempfile.TemporaryDirectory(prefix='evokb-runtime-structure-') as temp:
        root = Path(temp)
        actual_home = java17_bin().parent
        try:
            with patch.dict(os.environ, {'JAVA_HOME': str(actual_home)}, clear=True):
                java17_bin.cache_clear()
                checks['javaHomeSelectsVerifiedJava17'] = java17_bin().parent == actual_home
            with patch.dict(os.environ, {'EKB_PROBE_JAVA_HOME': str(actual_home),
                                         'JAVA_HOME': str(root / 'not-a-jdk')}, clear=True):
                java17_bin.cache_clear()
                checks['probeJavaHomeTakesPrecedenceOverJavaHome'] = java17_bin().parent == actual_home
            invalid_home = root / 'non17-fixture'
            invalid_bin = invalid_home / 'bin'
            invalid_bin.mkdir(parents=True)
            compiler = invalid_bin / 'javac'
            compiler.write_text('#!/bin/sh\nprintf "javac 21.0.1\\n"\n')
            compiler.chmod(0o700)
            with patch.dict(os.environ, {'JAVA_HOME': str(invalid_home)}, clear=True):
                java17_bin.cache_clear()
                try:
                    java17_bin()
                    checks['configuredWrongJavaVersionRejected'] = False
                except ValueError as error:
                    checks['configuredWrongJavaVersionRejected'] = 'Expected Java17' in str(error)
        finally:
            java17_bin.cache_clear()
        extracted = artifact.extract(root / 'artifact')
        checks['actualImmutableArtifactDependenciesExtracted'] = bool(extracted.library_hashes)
        checks['actualApplicationClassIsFromFrozenJar'] = any(
            name.startswith('ekb-application-') for name in extracted.library_hashes)
        source = root / 'HarnessSmoke.java'
        source.write_text('''import cn.lgs.knowledgebase.application.raw.RawChunkProfile;
public class HarnessSmoke {
 public static void main(String[] args) throws Exception {
  if(args.length>0) { System.out.println("started"); System.out.flush(); Thread.sleep(10000); }
  System.out.println(RawChunkProfile.DEFAULT.identity());
 }
}
''')
        classes = root / 'classes'
        compiled = compile_java(extracted.value, [source], classes, args.output.with_suffix('.compile.log'))
        checks['realBoundedJavacCompilesAgainstSelectedArtifact'] = compiled.returncode == 0
        compiled.check_returncode()
        classpath = str(classes) + os.pathsep + extracted.value
        ran = run_java(classpath, 'HarnessSmoke', [], args.output.with_suffix('.runtime.log'), heap_mib=64)
        checks['realJdkLoadsActualRawProfileWithoutModels'] = ran.returncode == 0 and 'raw-structure-v3' in ran.stdout
        try:
            run_java(classpath, 'HarnessSmoke', ['wait'], args.output.with_suffix('.timeout.log'),
                     heap_mib=64, timeout=0.5)
            checks['ownedJvmTimeoutIsRetainedAndTerminated'] = False
        except subprocess.TimeoutExpired:
            checks['ownedJvmTimeoutIsRetainedAndTerminated'] = 'COMMAND_TIMEOUT' in args.output.with_suffix('.timeout.log').read_text()
        try:
            run_java(classpath, 'HarnessSmoke', [], args.output.with_suffix('.runtime.log'), heap_mib=64)
            checks['priorCommandLogsCannotBeOverwritten'] = False
        except ValueError:
            checks['priorCommandLogsCannotBeOverwritten'] = True
        try:
            FrozenBootArtifact(args.jar, '0' * 64)
            checks['artifactDigestMismatchRefused'] = False
        except ValueError:
            checks['artifactDigestMismatchRefused'] = True
        sample = root / 'mutable-fixture.jar'
        with zipfile.ZipFile(sample, 'w') as archive:
            archive.writestr('BOOT-INF/lib/a.jar', b'frozen-library')
            archive.writestr('BOOT-INF/classes/value', b'original')
        snapshot = FrozenBootArtifact(sample)
        sample.write_bytes(b'changed-after-freeze')
        frozen = snapshot.extract(root / 'snapshot')
        checks['sourceReplacementCannotMixExtractionAndRecordedHash'] = (
            frozen.boot.joinpath('value').read_bytes() == b'original' and not snapshot.unchanged())
        unsafe = root / 'unsafe-fixture.jar'
        with zipfile.ZipFile(unsafe, 'w') as archive:
            archive.writestr('BOOT-INF/classes/../../outside', b'invalid')
            archive.writestr('BOOT-INF/lib/a.jar', b'frozen-library')
        try:
            FrozenBootArtifact(unsafe).extract(root / 'unsafe')
            checks['archiveTraversalRefusedWithoutOutsideWrite'] = False
        except ValueError:
            checks['archiveTraversalRefusedWithoutOutsideWrite'] = not root.joinpath('outside').exists()
    report = {'status': 'PASS_NATIVE_RUNTIME_STRUCTURE' if all(checks.values()) else 'FAIL_NATIVE_RUNTIME_STRUCTURE',
              'checks': checks, 'artifact': str(args.jar.resolve()), 'jarSha256': artifact.sha256,
              'runtimeSourceSha256': RUNTIME_SOURCE_SHA256, 'modelRequests': 0,
              'businessMutations': 0, 'boundary': 'Actual JDK and selected immutable artifact; synthetic ZIPs test harness safety only. No product cache, tokenizer, schema or model acceptance claim.'}
    args.output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report))
    raise SystemExit(0 if report['status'].startswith('PASS') else 1)


if __name__ == '__main__':
    main()
