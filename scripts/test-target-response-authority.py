#!/usr/bin/env python3
"""Read saved document classification/query scope via normal owned APIs and read-only PG."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--session', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    version, session = str(uuid.UUID(args.version)), str(uuid.UUID(args.session))
    if args.output.exists():
        raise ValueError('Use a new report; preserve every prior attempt')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    process = json.loads((ROOT / '.tmp/target/process.json').read_text())
    if hashlib.sha256(Path(process['artifact']).read_bytes()).hexdigest() != process['jarSha256']:
        raise ValueError('Immutable deployed artifact changed')
    sql = f"""BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'document',(SELECT jsonb_build_object('document_id',v.document_id,'version_id',v.version_id,
   'security_level',m.security_level,'acl_version',m.acl_version,'sha256',v.sha256)
   FROM document_version v JOIN document_metadata m USING(version_id) WHERE v.version_id='{version}'),
 'query',(SELECT jsonb_build_object('session_id',session_id,'user_id',user_id,'status',status,
   'question',original_question,'filters',filters_json) FROM query_session WHERE session_id='{session}'));
COMMIT;
"""
    args.output.with_suffix('.sql').write_text(sql)
    result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                             '-v', 'ON_ERROR_STOP=1', '-U', 'evokb_target', '-d', 'evokb_target'],
                            input=sql, text=True, capture_output=True, timeout=30, check=True)
    args.output.with_suffix('.sql.log').write_text(result.stdout + result.stderr)
    native = json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))
    accounts = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())
    owner = next((key for key, value in accounts.items()
                  if value['user_id'] == native['query']['user_id']), None)
    if owner is None or native['document']['security_level'] != 30:
        raise ValueError('Use an owned existing session and an existing restricted document')
    spec = importlib.util.spec_from_file_location('response_authority_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    admin = module.client(accounts['admin'])[0]
    public = module.client(accounts['public'])[0]
    reader = module.client(accounts[owner])[0]
    doc_status, doc = admin('/documents?' + urllib.parse.urlencode(dict(document_id=native['document']['document_id'], page_size=50)))
    public_status, denied = public('/document/detail?version_id=' + version)
    query_status, query = reader('/query/session?id=' + session)
    other = 'admin' if owner != 'admin' else 'public'
    other_status, _ = module.client(accounts[other])[0]('/query/session?id=' + session)
    row = next((row for row in doc.get('documents', []) if row['version_id'] == version), {})
    filters = query.get('filters') or {}
    saved = native['query']['filters']
    fields = [('source_department', 'sourceDepartment'), ('material_category', 'materialCategory'),
              ('document_type', 'documentType'), ('version_ids', 'versionIds')]
    checks = dict(
        documentApiExposesStoredClassification=doc_status == 200 and row.get('security_level') == native['document']['security_level'],
        restrictedDocumentStillDeniedToPublic=public_status == 403,
        queryRemainsReadableOnlyByOwner=query_status == 200 and other_status in {403, 404},
        queryStatusAndQuestionArePreserved=query.get('status') == native['query']['status'] and query.get('question') == native['query']['question'],
        allFourScopeFieldsMatchSavedNativeScope=all(filters.get(snake) == saved.get(snake, saved.get(camel)) for snake, camel in fields),
        trustedClearanceCannotBeSuppliedThroughExposedScope=not {'security_clearance', 'securityClearance'} & filters.keys(),
        scopeContainsOnlyPublicRequestFields=set(filters) <= {field[0] for field in fields},
        deploymentUnchanged=json.loads((ROOT / '.tmp/target/process.json').read_text()) == process,
    )
    report = dict(status='PASS_CURRENT_RESPONSE_AUTHORITY' if all(checks.values()) else 'FAIL_CURRENT_RESPONSE_AUTHORITY',
                  actualOperationTime=datetime.now(timezone.utc).isoformat(), process=process,
                  readerSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  native=native, documentMetadata=row, ownedQuery=query, checks=checks,
                  observedHttpStatuses=dict(document=doc_status, publicDocument=public_status,
                                            ownedQuery=query_status, otherQuery=other_status),
                  modelCalls=0, businessSqlMutations=0,
                  boundary='Existing sessions/classification only. No query started or retried, no permission changed, '
                           'no download/heat or source/derived body read. This does not prove a browser retry or model answer.')
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=report['status'], passed=sum(checks.values()),
                         failed=[key for key, value in checks.items() if not value])))
    raise SystemExit(int(not all(checks.values())))


if __name__ == '__main__':
    main()
