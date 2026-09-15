"""Actual cached JUnit Platform execution of frozen raw/parser source regressions."""
from native_probe_runtime import java17_bin
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

R = Path(__file__).resolve().parents[1]
JAVA = java17_bin()
p = argparse.ArgumentParser(); p.add_argument('--jar', type=Path, required=True); p.add_argument('--output', type=Path, required=True); a = p.parse_args()
if a.output.exists(): raise SystemExit('Fresh result required')
frozen = a.output.with_suffix('.frozen'); frozen.mkdir(parents=True)
paths = [
    'scripts/RawAtomicIdentityJunitLauncher.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/port/Ports.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw/RawEncodingProfile.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/raw/RawVectorEncoder.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/model/OpenAiCompatibleModelServingAdapter.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/model/EncodingIdentityCodec.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/model/BudgetedHttpRetry.java',
    'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/raw/RawVectorEncoderTest.java',
    'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/raw/RawAtomicVectorEncoderTest.java',
    'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/raw/RawTestFixtures.java',
    'java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/EncodingIdentityCodecTest.java',
    'java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/RawAtomicEmbeddingHttpTest.java',
    'java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/BudgetedHttpRetryTest.java',
    'java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/OpenAiCompatibleModelServingAdapterTest.java',
]
resource_relative = 'java/ekb-infrastructure/src/test/resources/model/atomic-encoding-response.json'
resource = frozen / 'atomic-encoding-response.json'; resource.write_bytes((R / resource_relative).read_bytes())
snapshots = []
for path in paths:
    target = frozen / Path(path).name; target.write_bytes((R / path).read_bytes()); snapshots.append(target)
m2 = Path.home() / '.m2/repository'
pinned = [m2 / f'org/junit/platform/junit-platform-{name}/1.12.2/junit-platform-{name}-1.12.2.jar' for name in ['launcher', 'engine', 'commons']]
pinned += [m2 / f'org/junit/jupiter/junit-jupiter-{name}/5.12.2/junit-jupiter-{name}-5.12.2.jar' for name in ['api', 'engine']]
pinned += [m2 / 'org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar', m2 / 'org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar']
if not all(path.exists() for path in pinned): raise SystemExit('Required JUnit dependencies are not cached; no downloads')
binding = {'jarSha256': hashlib.sha256(a.jar.read_bytes()).hexdigest(), 'artifact': str(a.jar.resolve()),
    'sourceHashes': {path: hashlib.sha256((frozen / Path(path).name).read_bytes()).hexdigest() for path in paths},
    'resourceSha256': {resource_relative: hashlib.sha256(resource.read_bytes()).hexdigest()}, 'junitDependencies': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in pinned},
    'provenance': 'FROZEN_SOURCE_OVERLAY_COMPONENT_JUNIT_NOT_DEPLOYED', 'startedAt': datetime.now(timezone.utc).isoformat()}
try:
    with tempfile.TemporaryDirectory(prefix='evokb-raw-junit-') as directory:
        work = Path(directory); classes = work / 'classes'; classes.mkdir(); (classes / 'model').mkdir(); (classes / 'model/atomic-encoding-response.json').write_bytes(resource.read_bytes())
        libraries = []
        with zipfile.ZipFile(a.jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    target = work / Path(name).name; target.write_bytes(archive.read(name)); libraries.append(target)
        cp = os.pathsep.join([*(str(path) for path in pinned), *(str(path) for path in libraries)])
        compiled = subprocess.run([str(JAVA / 'javac'), '-J-Xmx128m', '-J-XX:ActiveProcessorCount=2', '-cp', cp, '-d', str(classes), *(str(path) for path in snapshots)], capture_output=True, text=True, timeout=240)
        a.output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr); compiled.check_returncode()
        result = subprocess.run([str(JAVA / 'java'), '-Xmx128m', '-XX:ActiveProcessorCount=2', '-cp', str(classes) + os.pathsep + cp, 'RawAtomicIdentityJunitLauncher', str(a.output)], capture_output=True, text=True, timeout=240)
        a.output.with_suffix('.runtime.log').write_text(result.stdout + result.stderr)
        binding['processExitCode'] = result.returncode
except Exception as error:
    binding['failure'] = str(error)
report = json.loads(a.output.read_text()) if a.output.exists() else {'status': 'FAIL_NATIVE_FOCUSED_JUNIT', 'passed': 0}
report.update(binding); report['finishedAt'] = datetime.now(timezone.utc).isoformat()
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)); print(json.dumps({key: report.get(key) for key in ['status', 'found', 'passed', 'failed', 'failures', 'failure']}))
raise SystemExit(0 if report['status'].startswith('PASS') and report.get('processExitCode') == 0 else 1)
