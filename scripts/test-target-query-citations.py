#!/usr/bin/env python3
"""Compare a normal owned query's citations with current authoritative source metadata."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--account', choices=['admin', 'public', 'confidential'], default='admin')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    session = str(uuid.UUID(args.session))
    output = args.output.resolve()
    if any(p.exists() for p in [output, output.with_suffix('.sql'), output.with_suffix('.log')]):
        raise ValueError('Use a fresh output stem; retain all failures')
    output.parent.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    account = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())[args.account]
    request, _ = module.client(account)
    code, response = request('/query/session?id=' + session)
    sql = f"""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
  'session',(SELECT to_jsonb(s) FROM query_session s WHERE session_id='{session}'),
  'evidence',(SELECT coalesce(jsonb_agg(jsonb_build_object('evidence_id',e.evidence_id,
    'version_id',e.version_id,'document_id',e.document_id,'original_filename',v.original_filename,
    'object_key',e.object_key,'content_hash',e.content_hash,'active',e.active,
    'authority_acl',a.acl_version,'security_level',a.security_level)),'[]'::jsonb)
    FROM query_session s JOIN evidence e ON s.citation_evidence_ids @> to_jsonb(e.evidence_id)
    JOIN document_version v ON v.version_id=e.version_id
    LEFT JOIN kb_source_authority a ON a.version_id=e.version_id WHERE s.session_id='{session}')
);
COMMIT;
"""
    output.with_suffix('.sql').write_text(sql)
    native = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                             '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, text=True, capture_output=True, timeout=30)
    output.with_suffix('.log').write_text(native.stdout + native.stderr)
    native.check_returncode()
    data = json.loads(next(line for line in native.stdout.splitlines() if line.startswith('{')))
    authority = {e['evidence_id']: e for e in data['evidence']}
    citations = response.get('citations', []) if code == 200 else []
    checks = {
        'ownedActualSessionReadable': code == 200 and data['session']['user_id'] == account['user_id'],
        'actualAnsweredSessionHasCitations': response.get('status') == 'ANSWERED' and bool(citations),
        'citationsKeepExactlyPersistedEvidenceIds':
            {c['evidence']['evidence_id'] for c in citations} == set(data['session']['citation_evidence_ids']),
        'citationIndexesPreservePersistedAnswerReferenceOrder':
            [(c['index'], c['evidence']['evidence_id']) for c in citations]
            == list(enumerate(data['session']['citation_evidence_ids'], 1)),
        'everyCitationHasCurrentActiveSourceAuthority':
            bool(citations) and all(authority[c['evidence']['evidence_id']]['active']
                                   and authority[c['evidence']['evidence_id']]['authority_acl'] is not None
                                   for c in citations),
        'citationSourceIdentitiesAndContentHashesMatchNativeRecords': bool(citations) and all(
            all(c['evidence'][field] == authority[c['evidence']['evidence_id']][field]
                for field in ['version_id', 'document_id', 'content_hash', 'object_key']) for c in citations),
        'citationFilenamesMatchAuthoritativeOriginalMetadata': bool(citations) and all(
            c['evidence']['source_filename'] == authority[c['evidence']['evidence_id']]['original_filename']
            for c in citations),
        'citationRawTextAndLocationsRetained': bool(citations) and all(
            c['evidence'].get('raw_content') and c['evidence'].get('raw_location') for c in citations),
    }
    state = json.loads((ROOT / '.tmp/target/process.json').read_text())
    report = dict(status='PASS_ACTUAL_QUERY_CITATION_METADATA' if all(checks.values())
                  else 'FAIL_ACTUAL_QUERY_CITATION_METADATA',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(),
                  jarSha256=state['jarSha256'], checks=checks, session=session,
                  response=response, native=data,
                  scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  boundary='Existing real browser query, normal owned GET and read-only PostgreSQL. '
                           'No new inference, source change, task success or usage fact is manufactured. '
                           'This validates citation metadata, not all answer quality or model identity.')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()),
                         failed=[name for name, passed in checks.items() if not passed])))
    raise SystemExit(int(not all(checks.values())))


if __name__ == '__main__':
    main()
