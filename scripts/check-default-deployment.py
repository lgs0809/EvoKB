#!/usr/bin/env python3
"""Check the default local deployment contract without starting containers or models."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Optional fresh evidence file; contains hashes/check names, never rendered secrets')
    args = parser.parse_args()
    if args.output and args.output.exists():
        raise ValueError('Fresh evidence path required')
    command = ['docker', 'compose', '--env-file', '.env.example', '-f',
               'deploy/docker-compose.yml', 'config', '--format', 'json']
    # This check covers committed defaults, independent of exported private runtime overrides.
    default_env = {key: value for key, value in os.environ.items() if not key.startswith('EKB_')}
    result = subprocess.run(command, cwd=ROOT, env=default_env,
                            capture_output=True, text=True, timeout=30)
    if result.returncode:
        # Do not print rendered values from a local env file.
        raise SystemExit('Default Compose configuration failed; inspect docker compose config -q')
    config = json.loads(result.stdout)
    services = config['services']
    app = services['app']
    env = app['environment']
    postgres = services['postgres']['environment']
    minio = services['minio']['environment']
    milvus = services['milvus']['environment']
    checks = {
        'onlyCurrentInfrastructureAndApplication': set(services) == {'postgres', 'redis', 'minio', 'etcd', 'milvus', 'app'},
        'independentCurrentProject': config['name'] == 'evokb-local',
        'allPublishedPortsAreLoopback': all(port.get('host_ip') == '127.0.0.1'
            for service in services.values() for port in service.get('ports', [])),
        'postgresDatabaseUrlAndDriverContract': env['EKB_JDBC_URL'] ==
            'jdbc:postgresql://postgres:5432/' + postgres['POSTGRES_DB'] + '?stringtype=unspecified',
        'postgresCredentialsUseCurrentApplicationKeys': env['EKB_DB_USER'] == postgres['POSTGRES_USER']
            and env['EKB_DB_PASSWORD'] == postgres['POSTGRES_PASSWORD'] and 'EKB_MYSQL_USER' not in env,
        'redisUsesPrivateServiceAddress': env['EKB_REDIS_HOST'] == 'redis' and str(env['EKB_REDIS_PORT']) == '6379',
        'milvusUsesCurrentSdkAddress': env['EKB_MILVUS_URL'] == 'http://milvus:19530',
        'milvusSharesConfiguredObjectServiceAndSeparateBucket': milvus['MINIO_ADDRESS'] == 'minio:9000'
            and milvus['MINIO_ACCESS_KEY_ID'] == minio['MINIO_ROOT_USER']
            and milvus['MINIO_SECRET_ACCESS_KEY'] == minio['MINIO_ROOT_PASSWORD']
            and milvus['MINIO_BUCKET_NAME'] != env['EKB_MINIO_BUCKET'],
        'milvusHasHealthyEtcdAndObjectDependencies': set(services['milvus']['depends_on']) == {'etcd', 'minio'}
            and all(value['condition'] == 'service_healthy' for value in services['milvus']['depends_on'].values()),
        'applicationWaitsForAllCurrentDependencies': set(app['depends_on']) == {'postgres', 'redis', 'minio', 'milvus'}
            and all(value['condition'] == 'service_healthy' for value in app['depends_on'].values()),
        'containerListensOnPrivateNetwork': env['EKB_SERVER_HOST'] == '0.0.0.0' and str(env['EKB_SERVER_PORT']) == '18080',
        'approvedChatModelsAreExplicit': env['EKB_SMALL_MODEL'] == 'gpt-5.6-luna' and env['EKB_LARGE_MODEL'] == 'gpt-5.6-terra',
        'approvedEmbeddingDimensionsAndModelsAreExplicit': env['EKB_EMBEDDING_MODEL'] == 'Qwen/Qwen3-VL-Embedding-2B'
            and str(env['EKB_EMBED_DIMS']) == '1024' and env['EKB_RERANK_MODEL'] == 'Qwen/Qwen3-VL-Reranker-2B',
        'missingExternalServicesDoNotInventModels': all(not env[key] for key in
            ['EKB_CHAT_BASE_URL', 'EKB_CHAT_API_KEY', 'EKB_EMBEDDING_BASE_URL', 'EKB_EMBEDDING_API_KEY',
             'EKB_RERANK_BASE_URL', 'EKB_RERANK_API_KEY', 'EKB_MINERU_URL']) and env['EKB_ASSISTANT_FALLBACK'] == 'disabled',
        'mineruKeepsFrozenBaselineAnd600Seconds': env['EKB_MINERU_VERSION'] == '3.4.5'
            and env['EKB_MINERU_BACKEND'] == 'hybrid-engine' and env['EKB_MINERU_EFFORT'] == 'medium'
            and env['EKB_MINERU_PARSE_METHOD'] == 'auto' and str(env['EKB_MINERU_TASK_TIMEOUT_SECONDS']) == '600',
        'effectiveSingleFileLimitsRemain256MiB': str(env['EKB_MAX_UPLOAD_BYTES']) == str(env['EKB_MINERU_MAX_FILE_BYTES']) == '268435456',
        'tokenizerMountIsReadOnlyAndManifestIsNotInvented': any(volume['target'] == '/app/tokenizer'
            and volume.get('read_only') is True and volume['type'] == 'bind' for volume in app['volumes'])
            and not env['EKB_EMBED_TOKENIZER_MANIFEST'],
        'allCurrentVolumesUseIndependentProjectNames': all(value['name'].startswith('evokb-local_')
            and not value.get('external', False) for value in config['volumes'].values()),
    }
    ci = (ROOT / '.github/workflows/ci.yml').read_text()
    makefile = (ROOT / 'Makefile').read_text()
    checks['ciUsesCurrentDatabaseAndIsolatedProject'] = 'EKB_POSTGRES_DATABASE: evokb_ci_smoke' in ci and 'EKB_MYSQL_DATABASE' not in ci and 'evokb-ci-${{ github.run_id }}' in ci
    checks['ciCredentialsAreGeneratedWithoutModelEndpoints'] = 'secrets.token_urlsafe(32)' in ci and '.env.ci.private' in ci
    checks['makeDefaultsToCurrentComposeAndPreservingStop'] = 'COMPOSE_FILE ?= deploy/docker-compose.yml' in makefile and 'stop\n' in makefile and 'down -v' not in makefile
    files = ['deploy/docker-compose.yml', '.env.example', 'Makefile', '.github/workflows/ci.yml',
             'deploy/Dockerfile', 'java/ekb-bootstrap/src/main/resources/application.yml',
             'java/ekb-bootstrap/src/main/java/cn/lgs/knowledgebase/EnterpriseConfiguration.java']
    report = {'createdAt': datetime.now(timezone.utc).isoformat(),
              'status': 'PASS_STATIC_DEFAULT_DEPLOYMENT_CONTRACT' if all(checks.values()) else 'FAIL_STATIC_DEFAULT_DEPLOYMENT_CONTRACT',
              'checks': checks, 'sourceSha256': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in files},
              'checkSourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'containerStarts': 0, 'imageBuilds': 0, 'modelRequests': 0, 'businessMutations': 0,
              'boundary': 'Compose parses actual config and static startup dependencies. Fresh Docker build/start, migration execution, CI/browser smoke and real model workflows were not executed by this check.'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2))
    print(json.dumps({key: report[key] for key in ['status', 'checks', 'containerStarts', 'imageBuilds', 'modelRequests']}))
    raise SystemExit(0 if all(checks.values()) else 1)


if __name__ == '__main__':
    main()
