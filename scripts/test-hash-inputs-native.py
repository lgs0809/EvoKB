"""Freeze exact immutable JAR/native tokenizer input plan without Qwen/model requests."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
from native_probe_runtime import FrozenBootArtifact, RUNTIME_SOURCE_SHA256, compile_java, run_java

R = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(); p.add_argument('--jar', type=Path, required=True); p.add_argument('--fixtures', type=Path, required=True)
p.add_argument('--tokenizer-manifest', type=Path, required=True); p.add_argument('--output', type=Path, required=True); a = p.parse_args()
if a.output.exists(): raise SystemExit('Fresh result required')
frozen = a.output.with_suffix('.frozen'); frozen.mkdir(parents=True)
helper = frozen / 'RawHashInputFreezeProbe.java'; helper.write_bytes((R / 'scripts/RawHashInputFreezeProbe.java').read_bytes())
artifact = FrozenBootArtifact(a.jar)
binding = {'jarSha256': artifact.sha256, 'artifact': str(a.jar.resolve()),
    'nativeRuntimeSourceSha256': RUNTIME_SOURCE_SHA256,
    'manifestSha256': hashlib.sha256((a.fixtures / 'manifest.json').read_bytes()).hexdigest(), 'helperSha256': hashlib.sha256(helper.read_bytes()).hexdigest(),
    'startedAt': datetime.now(timezone.utc).isoformat(), 'provenance': 'IMMUTABLE_JAR_CLASSES_NO_SOURCE_OVERLAY', 'actualModelRequests': 0}
try:
    with tempfile.TemporaryDirectory(prefix='evokb-hash-inputs-') as directory:
        work = Path(directory); classes = work / 'classes'
        extracted = artifact.extract(work)
        binding['dependencySha256'] = extracted.library_hashes
        cp = extracted.libraries_only
        compiled = compile_java(cp, [helper], classes, a.output.with_suffix('.compile.log'))
        compiled.check_returncode()
        ran = run_java(str(classes) + os.pathsep + cp,
            'cn.lgs.knowledgebase.application.raw.RawHashInputFreezeProbe',
            [a.fixtures, a.tokenizer_manifest, a.output], a.output.with_suffix('.runtime.log'), heap_mib=128)
        binding['processExitCode'] = ran.returncode
except Exception as error: binding['failure'] = str(error)
report = json.loads(a.output.read_text()) if a.output.exists() else {'status': 'FAIL_FROZEN_ACTUAL_HASH_INPUTS_NO_INFERENCE'}
report.update(binding); report['finishedAt'] = datetime.now(timezone.utc).isoformat(); a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({key: report.get(key) for key in ['status', 'documentCount', 'nativeOriginalParseExecutions', 'checks', 'fullRebuildInputCountsByRate', 'changedInputCountsByRate', 'failure']}))
raise SystemExit(0 if report['status'].startswith('PASS') and report.get('processExitCode') == 0 else 1)
