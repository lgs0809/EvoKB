#!/usr/bin/env python3
"""Read only: prove UI-selected profile and model invariants, without exporting keys."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ['profile_id', 'name', 'active', 'small_model', 'large_model', 'chat_timeout_seconds',
          'embedding_base_url', 'embedding_model', 'embedding_dimensions', 'embedding_batch_size',
          'embedding_timeout_seconds', 'rerank_base_url', 'rerank_model', 'rerank_timeout_seconds']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-embedding-base', required=True,
                        choices=['http://127.0.0.1:8110', 'http://127.0.0.1:18489'])
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if any(output.with_suffix(ext).exists() for ext in ['.json', '.sql', '.log']):
        raise ValueError('Use a fresh output stem; preserve every previous observation')
    output.parent.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    account = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())['admin']
    request, _ = module.client(account)
    code, view = request('/model-configurations')
    safe_profiles = [{key: profile[key] for key in FIELDS} for profile in view.get('profiles', [])]
    sql = """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'profiles', (SELECT coalesce(jsonb_agg(p),'[]') FROM (SELECT %s FROM model_configuration_profile) p),
 'activeCompilation', (SELECT count(*) FROM compile_task WHERE status IN ('RUNNING','PENDING','RETRY_WAIT')),
 'activeIngestion', (SELECT count(*) FROM ingestion_file WHERE status IN ('RUNNING','QUEUED')),
 'copiedSecretsAndChatBaseUnchanged', NOT EXISTS(SELECT 1 FROM model_configuration_profile p
   JOIN model_configuration_profile original ON original.name='默认配置'
   WHERE p.name LIKE '本机503故障验收%%' AND
    (p.chat_base_url IS DISTINCT FROM original.chat_base_url
     OR p.chat_api_key IS DISTINCT FROM original.chat_api_key
     OR p.embedding_api_key IS DISTINCT FROM original.embedding_api_key
     OR p.rerank_api_key IS DISTINCT FROM original.rerank_api_key))
);
COMMIT;
""" % ','.join(FIELDS)
    output.with_suffix('.sql').write_text(sql)
    result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                             '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, text=True, capture_output=True, timeout=30)
    output.with_suffix('.log').write_text(result.stdout + result.stderr)
    result.check_returncode()
    native = json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))
    active = [profile for profile in safe_profiles if profile['active']]
    selected = active[0] if len(active) == 1 else {}
    checks = {
        'oneActiveProfileMatchesNormalApi': code == 200 and len(active) == 1
            and selected.get('profile_id') == view.get('active_profile_id'),
        'nativeProfilesEqualNormalApi': sorted(safe_profiles, key=lambda p: p['profile_id'])
            == sorted(native['profiles'], key=lambda p: p['profile_id']),
        'selectedExpectedLoopbackEmbedding': selected.get('embedding_base_url') == args.expected_embedding_base,
        'confirmedModelRolesPreserved': selected.get('small_model') == 'gpt-5.6-luna'
            and selected.get('large_model') == 'gpt-5.6-terra',
        'qwenModelsDimensionsAndBudgetsPreserved': all(selected.get(key) == value for key, value in {
            'chat_timeout_seconds': 120, 'embedding_model': 'Qwen/Qwen3-VL-Embedding-2B',
            'embedding_dimensions': 1024, 'embedding_batch_size': 1, 'embedding_timeout_seconds': 120,
            'rerank_base_url': 'http://127.0.0.1:8110', 'rerank_model': 'Qwen/Qwen3-VL-Reranker-2B',
            'rerank_timeout_seconds': 30}.items()),
        'noActiveIngestionOrCompilation': native['activeCompilation'] == native['activeIngestion'] == 0,
        'copyDoesNotChangeSavedSecretsOrChatProvider': native['copiedSecretsAndChatBaseUnchanged'],
    }
    if args.baseline:
        baseline = json.loads(args.baseline.read_text())['activeProfile']
        checks['originalProfileRestoredExactly'] = selected == baseline
    report = dict(status='PASS_PROFILE_READBACK' if all(checks.values()) else 'FAIL_PROFILE_READBACK',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(), checks=checks,
                  activeProfile=selected, safeNormalProfiles=safe_profiles, native=native,
                  process=json.loads((ROOT / '.tmp/target/process.json').read_text()),
                  scope='Authenticated normal GET and read-only native PG. No key, masked key, '
                        'provider body, permission change, task transition or model-success claim.')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()),
                         failed=[key for key, value in checks.items() if not value],
                         activeProfileId=selected.get('profile_id'))))
    raise SystemExit(int(not all(checks.values())))


if __name__ == '__main__':
    main()
