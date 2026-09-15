#!/usr/bin/env python3
"""Restore exact owned synthetic sources to their frozen classification via the normal API.

Never resets tasks, imports altered bytes or writes SQL. Refuses unexpected source
identity or metadata changes. It cannot repair exhausted/PENDING source tasks.
"""
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
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--filename', action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or any(args.output.with_suffix(s).exists() for s in ('.before.sql', '.after.sql', '.before.log', '.after.log')):
        raise ValueError('Preserve previous evidence and choose a fresh output stem')
    manifest = json.loads(args.manifest.read_text())
    selected = [d for d in manifest['documents'] if d['filename'] in args.filename]
    if len(selected) != len(set(args.filename)):
        raise ValueError('Select exact frozen synthetic source filenames')
    for document in selected:
        assert hashlib.sha256((args.manifest.parent / document['path']).read_bytes()).hexdigest() == document['sha256']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('permissions', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    accounts = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())
    admin, _ = module.client(accounts['admin'])
    public, _ = module.client(accounts['public'])
    report = {'status': 'RUNNING', 'startedAt': datetime.now(timezone.utc).isoformat(),
              'manifestSha256': hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
              'readerProcess': json.loads((ROOT / '.tmp/target/process.json').read_text()),
              'operations': [], 'checks': {}, 'boundary': 'Normal ADMIN classification PATCH of exact owned '
                'synthetic ACTIVE sources. Every SQL statement is read-only. No task/epoch/source SHA/Gold '
                'or publication-success state is manufactured. Original incorrect import remains failed evidence.'}

    def save():
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    def snapshot(label):
        sql = """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY version_id),'[]'::jsonb) FROM (
SELECT d.document_id,d.logical_name,v.version_id,v.original_filename,v.sha256,v.lifecycle,
       m.source_department,m.material_category,m.company,m.manual_document_type_override,
       m.auto_document_type,m.document_date,m.manual_tags,m.security_level,m.acl_version
FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id)) t;
COMMIT;
"""
        args.output.with_suffix('.' + label + '.sql').write_text(sql)
        result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                                 '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                                input=sql, text=True, capture_output=True, timeout=30)
        args.output.with_suffix('.' + label + '.log').write_text(result.stdout + result.stderr)
        result.check_returncode()
        return json.loads(next(line for line in result.stdout.splitlines() if line.startswith('[')))

    try:
        before = snapshot('before')
        report['before'] = before
        plans = []
        for document in selected:
            matches = [r for r in before if r['logical_name'] == document['logicalName']]
            if len(matches) != 1:
                raise ValueError('Source is absent or ambiguous; nothing is overwritten')
            current = matches[0]
            uuid.UUID(current['version_id'])
            if (current['sha256'] != document['sha256'] or current['lifecycle'] != 'ACTIVE'
                    or current['original_filename'] != document['filename']
                    or 'EvoKB-formal-v1' not in current['manual_tags']):
                raise ValueError('Unexpected source identity; preserve it for inspection')
            if current['security_level'] != document['securityLevel'] and not (
                    current['security_level'] == 10 and current['acl_version'] == 1 and document['securityLevel'] == 30):
                raise ValueError('Unexpected classification change; no overwrite is authorized')
            plans.append((document, current))
        save()
        for document, current in plans:
            version = current['version_id']
            path = '/document/detail?' + urllib.parse.urlencode({'version_id': version, 'block_limit': 1})
            before_code, before_body = public(path)
            operation = {'filename': document['filename'], 'versionId': version,
                         'publicDetailBefore': before_code, 'publicDetailBeforeBody': before_body,
                         'classificationBefore': current['security_level'],
                         'aclBefore': current['acl_version']}
            report['operations'].append(operation)
            save()
            if current['security_level'] != document['securityLevel']:
                payload = {'logical_name': current['logical_name'], 'source_department': current['source_department'],
                           'material_category': current['material_category'],
                           'document_type': current['manual_document_type_override'] or current['auto_document_type'],
                           'document_date': current['document_date'], 'manual_tags': current['manual_tags'],
                           'security_level': document['securityLevel']}
                code, body = admin('/document?' + urllib.parse.urlencode({'version_id': version}), payload, method='PATCH')
                operation.update(patchHttpStatus=code, patchResponse=body)
                save()
                if code != 200:
                    raise ValueError('Normal classification update failed; preserve any committed state')
            public_code, public_body = public(path)
            admin_code, admin_body = admin(path)
            operation.update(publicDetailAfter=public_code, publicDetailAfterBody=public_body,
                             adminDetailAfter=admin_code, adminDetailAfterBody=admin_body)
            save()
        after = snapshot('after')
        report['after'] = after
        own = {current['version_id'] for _, current in plans}
        report['checks']['otherBusinessMetadataAndAuthorityPreserved'] = all(row in after for row in before if row['version_id'] not in own)
        report['checks']['wholeOriginalIdentityAndMetadataPreserved'] = all(
            {k:v for k,v in old.items() if k not in ('security_level','acl_version','manual_document_type_override')} ==
            {k:v for k,v in next(row for row in after if row['version_id'] == old['version_id']).items()
                if k not in ('security_level','acl_version','manual_document_type_override')}
            for _,old in plans)
        report['checks']['frozenClassificationAndExpectedAclRevision'] = all(
            any(row['version_id'] == old['version_id'] and row['security_level'] == doc['securityLevel']
                and row['acl_version'] == old['acl_version'] + int(old['security_level'] != doc['securityLevel'])
                for row in after) for doc,old in plans)
        report['checks']['normalReadGuardsEnforceClassification'] = all(
            op['publicDetailAfter'] == 403 and op['adminDetailAfter'] == 200 for op in report['operations'])
        report['status'] = 'PASS_NORMAL_CLASSIFICATION_REPAIR' if all(report['checks'].values()) else 'FAIL_NORMAL_CLASSIFICATION_REPAIR'
    except Exception as error:
        report['status'] = 'FAIL_NORMAL_CLASSIFICATION_REPAIR'
        report['failureType'] = type(error).__name__
        report['failure'] = str(error)
    finally:
        report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        save()
        print(json.dumps({'status': report['status'], 'actualOperations': len(report['operations']),
                          'checks': report['checks']}, ensure_ascii=False))
    raise SystemExit(int(not report['status'].startswith('PASS')))


if __name__ == '__main__':
    main()
