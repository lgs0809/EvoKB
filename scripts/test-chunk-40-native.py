"""Run 40x9 offline development chunks against one immutable deployed JAR.

No production source overlays, Maven, database writes or model requests.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
from native_probe_runtime import FrozenBootArtifact, RUNTIME_SOURCE_SHA256, compile_java, run_java

R = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--jar', type=Path, required=True)
p.add_argument('--fixtures', type=Path, required=True)
p.add_argument('--tokenizer-manifest', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--source-overlay', action='store_true', help='Explicit component-only frozen production source overlay, never a deployed artifact claim')
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh result required; preserve every prior failure')
a.output.parent.mkdir(parents=True, exist_ok=True)
frozen = a.output.with_suffix('.frozen')
frozen.mkdir()
source = R / 'scripts/RawChunkMatrixProbe.java'
helper = frozen / source.name
helper.write_bytes(source.read_bytes())
overlay = []
if a.source_overlay:
    for relative in [
        'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw/RawKnowledgeBuilder.java',
        'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw/RawChunkProfile.java',
        'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/parser/TextDocumentParserAdapter.java',
    ]:
        target = frozen / Path(relative).name
        target.write_bytes((R / relative).read_bytes())
        overlay.append(target)
manifest = json.loads((a.fixtures / 'manifest.json').read_text())
for case in manifest['cases']:
    data = (a.fixtures / case['filename']).read_bytes()
    if len(data) != case['sizeBytes'] or hashlib.sha256(data).hexdigest() != case['sha256']:
        raise SystemExit('Frozen source changed: ' + case['id'])
tokenizer = json.loads(a.tokenizer_manifest.read_text())
tokenizer_files = {name: hashlib.sha256((a.tokenizer_manifest.parent / name).read_bytes()).hexdigest()
                   for name in tokenizer['files']}
if tokenizer_files != tokenizer['files']:
    raise SystemExit('Frozen tokenizer assets changed')
artifact = FrozenBootArtifact(a.jar)
binding = {'artifact': str(a.jar.resolve()), 'jarSha256': artifact.sha256,
    'nativeRuntimeSourceSha256': RUNTIME_SOURCE_SHA256,
    'probeSourceSha256': hashlib.sha256(helper.read_bytes()).hexdigest(),
    'manifest': str((a.fixtures / 'manifest.json').resolve()),
    'manifestSha256': hashlib.sha256((a.fixtures / 'manifest.json').read_bytes()).hexdigest(),
    'tokenizerManifest': str(a.tokenizer_manifest.resolve()),
    'tokenizerManifestSha256': hashlib.sha256(a.tokenizer_manifest.read_bytes()).hexdigest(),
    'tokenizerFiles': tokenizer_files, 'modelRevision': tokenizer['modelRevision'],
    'tokenizerRevision': tokenizer['tokenizerRevision'], 'startedAt': datetime.now(timezone.utc).isoformat()}
binding['provenance'] = 'EXPLICIT_FROZEN_SOURCE_OVERLAY_COMPONENT_ONLY' if a.source_overlay else 'FROZEN_DEPLOYED_JAR_CLASSES_NO_OVERLAY'
binding['deploymentClaim'] = not a.source_overlay
binding['productionSourceOverlayHashes'] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in overlay}
failure = None
try:
    with tempfile.TemporaryDirectory(prefix='evokb-offline-chunks-') as temporary:
        work = Path(temporary)
        classes = work / 'classes'
        extracted = artifact.extract(work)
        production_hashes = {name: value for name,value in extracted.class_hashes.items()
                             if '/raw/' in name or '/parser/' in name}
        binding.update(productionClassHashes=production_hashes, dependencyHashes=extracted.library_hashes)
        cp = extracted.value
        compiled = compile_java(cp, [helper, *overlay], classes, a.output.with_suffix('.compile.log'))
        if compiled.returncode:
            raise RuntimeError('Probe compilation failed; preserved compile log')
        binding['helperClassHashes'] = {str(path.relative_to(classes)): hashlib.sha256(path.read_bytes()).hexdigest() for path in classes.rglob('*.class')}
        native = run_java(str(classes) + os.pathsep + cp,
            'cn.lgs.knowledgebase.application.raw.RawChunkMatrixProbe',
            [a.fixtures, a.tokenizer_manifest, a.output], a.output.with_suffix('.runtime.log'), timeout=600)
        binding['processExitCode'] = native.returncode
except Exception as error:
    failure = str(error)
report = json.loads(a.output.read_text()) if a.output.exists() else {'status': 'FAIL_OFFLINE_CHUNK_40_BY_9', 'configurationExecutions': 0}
report.update(binding)
report['finishedAt'] = datetime.now(timezone.utc).isoformat()
report['failure'] = failure
if failure or binding.get('processExitCode') != 0:
    report['status'] = 'FAIL_OFFLINE_CHUNK_40_BY_9'
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
groups = []
for profile in manifest['profiles']:
    rows = [row for row in report.get('results', []) if row.get('targetTokens') == profile['target'] and row.get('overlapTokens') == profile['overlap']]
    groups.append({'target': profile['target'], 'overlap': profile['overlap'], 'executions': len(rows),
        'passed': sum(row['status'] == 'PASS' for row in rows),
        'chunks': sum(row.get('chunkCount', 0) for row in rows),
        'encodingTokens': sum(row.get('encodingTokens', 0) for row in rows),
        'maxRenderedTokens': max((row.get('maxRenderedTokens', 0) for row in rows), default=0)})
summary = {'status': report['status'], 'documentCaseCount': 40, 'configurationExecutions': report['configurationExecutions'],
    'passedExecutions': report.get('passedExecutions', 0), 'profiles': groups, 'failure': failure}
a.output.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
