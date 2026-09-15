"""Frozen Summary/Wiki constraint and fallback regressions using cached native JUnit engines."""
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

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--include-compilation', action='store_true')
parser.add_argument('--include-model-adapter', action='store_true')
parser.add_argument('--model-contract-only', action='store_true',
                    help='Select response/budget contract methods without transport-backoff timing cases')
parser.add_argument('--original-model-adapter', type=Path,
                    help='Explicit retained original adapter for a negative contract reproduction')
args = parser.parse_args()
if args.original_model_adapter and not args.include_model_adapter:
    parser.error('--original-model-adapter requires --include-model-adapter')
if args.model_contract_only and not args.include_model_adapter:
    parser.error('--model-contract-only requires --include-model-adapter')
if args.output.exists() or args.output.with_suffix('.frozen').exists():
    raise SystemExit('Fresh report required; old failures must be retained')
frozen = args.output.with_suffix('.frozen')
frozen.mkdir(parents=True)
sources = [
    'scripts/GroundingFocusedJunitLauncher.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/port/Ports.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/GroundingConstraintAudit.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/ModelOutputRejectedException.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/GenerationTaskRetryPolicy.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/CompilationExecutionBudget.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/CompilationContracts.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/SummaryCompilationService.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/WikiCompilationService.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/ConceptCompilationService.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/GraphCompilationService.java',
    'java/ekb-application/src/main/java/cn/lgs/knowledgebase/application/compilation/WikiKeywordProjectionService.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcWikiKeywordProjectionRepository.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcGenerationTaskRepository.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/model/AssistantEvidenceFallbackKnowledgeModel.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcCompilationRepository.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcKnowledgeEntryCatalogue.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/SourceAccessSql.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcEnterpriseOperations.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcArtifactEvaluationRepository.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcCompilationCommandRepository.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcKnowledgeRepository.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/graph/PostgresGraphStoreAdapter.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/persistence/JdbcAssetCatalogue.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/search/MilvusKnowledgeIndexAdapter.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/compilation/DurableCompilationWorker.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/compilation/DurableEmbeddingRebuildWorker.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/compilation/GenerationTaskFailureHandler.java',
    'java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/model/BudgetedHttpRetry.java',
]
tests = [
    'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/compilation/GroundingConstraintAuditTest.java',
    'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/compilation/GenerationTaskRetryPolicyTest.java',
    'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/compilation/CompilationExecutionBudgetTest.java',
    'java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/AssistantEvidenceFallbackKnowledgeModelTest.java',
    'java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/GroundingStructuredOutputTest.java',
]
if args.include_compilation:
    tests += [
        'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/compilation/CompilationServicesTest.java',
        'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/compilation/CompilationServicesParityTest.java',
        'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/compilation/ConceptCompilationServiceTest.java',
        'java/ekb-application/src/test/java/cn/lgs/knowledgebase/application/compilation/WikiKeywordProjectionServiceTest.java',
        'java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/compilation/DurableCompilationWorkerTest.java',
    ]
if args.include_model_adapter:
    sources.append('java/ekb-infrastructure/src/main/java/cn/lgs/knowledgebase/infrastructure/model/SpringAiAlibabaKnowledgeModel.java')
    tests.append('java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/SpringAiAlibabaKnowledgeModelTest.java')
    tests.append('java/ekb-infrastructure/src/test/java/cn/lgs/knowledgebase/infrastructure/model/BudgetedHttpRetryTest.java')
paths = sources + tests
for path in paths:
    original = args.original_model_adapter if args.original_model_adapter and Path(path).name == 'SpringAiAlibabaKnowledgeModel.java' else ROOT / path
    (frozen / Path(path).name).write_bytes(original.read_bytes())
m2 = Path.home() / '.m2/repository'
pinned = [m2 / f'org/junit/platform/junit-platform-{n}/1.12.2/junit-platform-{n}-1.12.2.jar' for n in ['launcher', 'engine', 'commons']]
pinned += [m2 / f'org/junit/jupiter/junit-jupiter-{n}/5.12.2/junit-jupiter-{n}-5.12.2.jar' for n in ['api', 'engine']]
pinned += [m2 / 'org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar', m2 / 'org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar']
if not all(p.exists() for p in pinned):
    raise SystemExit('JUnit not cached; no implicit download')
report = dict(status='FAIL_FROZEN_GROUNDING_COMPONENT', startedAt=datetime.now(timezone.utc).isoformat(),
              boundary='Frozen candidate source over previous immutable release dependencies. No Maven build, deployment, models or business state writes.',
              artifact=str(args.jar.resolve()), jarSha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(),
              originalAdapter=str(args.original_model_adapter.resolve()) if args.original_model_adapter else None,
              sourceHashes={p: hashlib.sha256((frozen / Path(p).name).read_bytes()).hexdigest() for p in paths})
java = java17_bin()
try:
    with tempfile.TemporaryDirectory(prefix='evokb-grounding-') as temp:
        work = Path(temp)
        classes = frozen / 'classes'
        classes.mkdir()
        libraries = []
        with zipfile.ZipFile(args.jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    target = work / Path(name).name
                    target.write_bytes(archive.read(name))
                    libraries.append(target)
        cp = os.pathsep.join(map(str, pinned + libraries))
        compiled = subprocess.run([str(java/'javac'), '-J-Xmx128m', '-J-XX:ActiveProcessorCount=2', '-cp', cp, '-d', str(classes),
                                   *(str(frozen/Path(p).name) for p in paths)], capture_output=True, text=True, timeout=90)
        args.output.with_suffix('.compile.log').write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        test_classes = ['.'.join(Path(p).parts[5:]).removesuffix('.java') for p in tests]
        if args.model_contract_only:
            model_class = 'cn.lgs.knowledgebase.infrastructure.model.SpringAiAlibabaKnowledgeModelTest'
            test_classes.remove(model_class)
            test_classes += [model_class+'#'+name for name in [
                'invalidStructuredOutputIsNotARecoverableTransportFailure',
                'malformedJsonAndSchemaAreBoundedProposalFailuresWithoutExtraHttpRequests',
                'localMapperDefinitionAndProgrammingErrorsAreNotProposalRetries',
                'structuredAuthenticationAndBusinessFailuresRemainTerminal',
                'requestEncodingFailureIsNotARejectedModelResponseAndSendsNothing',
                'anExpiredStageCannotSendAnotherModelRequest',
                'successfulHeadersCannotKeepChatBodyPastTheOriginalStageDeadline',
                'revokedCapturedAuthorityIsTerminalAndNeverReachesHttp',
            ]]
        result = subprocess.run([str(java/'java'), '-Xmx128m', '-XX:ActiveProcessorCount=2', '-cp', str(classes)+os.pathsep+cp,
                                 'GroundingFocusedJunitLauncher', str(args.output.resolve()), *test_classes], capture_output=True, text=True, timeout=90)
        args.output.with_suffix('.runtime.log').write_text(result.stdout + result.stderr)
        if args.output.exists():
            report.update(json.loads(args.output.read_text()))
        report['exitCode'] = result.returncode
except Exception as failure:
    report.update(failureType=type(failure).__name__, failure=('Native subprocess exit '+str(failure.returncode) if isinstance(failure, subprocess.CalledProcessError) else str(failure)))
report.update(finishedAt=datetime.now(timezone.utc).isoformat(), sourceUnchanged={p: hashlib.sha256((args.original_model_adapter if args.original_model_adapter and Path(p).name == 'SpringAiAlibabaKnowledgeModel.java' else ROOT/p).read_bytes()).hexdigest() == report['sourceHashes'][p] for p in paths})
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
print(json.dumps({k: report.get(k) for k in ['status','found','passed','failed','skipped','failure','failures']}, ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') and report.get('exitCode') == 0 and all(report['sourceUnchanged'].values()) else 1)
