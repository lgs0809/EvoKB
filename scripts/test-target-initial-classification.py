#!/usr/bin/env python3
"""Verify initial classification on the deployed normal HTTP surface; SQL is read-only.

Only marked STAGED fixtures and multipart sessions are created. No Raw start/model call,
business classification patch, task reset, success seed, or storage cleanup is performed.
The browser-created completed source can be inspected with --logical-name.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--logical-name', required=True)
    parser.add_argument('--early-clearance', action='store_true', help='Verify the existing clearance10 administrator fails before any multipart object/session is created')
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Retain prior failures; use a fresh evidence name')
    module_spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    accounts = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())
    admin, admin_opener = module.client(accounts['admin'])
    public, public_opener = module.client(accounts['public'])
    process = json.loads((ROOT / '.tmp/target/process.json').read_text())
    if hashlib.sha256(Path(process['artifact']).read_bytes()).hexdigest() != process['jarSha256']:
        raise ValueError('Actual immutable artifact changed')
    report = dict(status='RUNNING', startedAt=datetime.now(timezone.utc).isoformat(), process=process,
                  checks={}, responses=[], retainedFixtures=[], mutationScope='Only new marked STAGED test files and own multipart sessions; all SQL read-only', modelRequestsByScript=0)

    def persist():
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    def check(name, value):
        report['checks'][name] = bool(value)
        persist()

    def observe(label, api, path, data=None, method=None):
        status, body = api(path, data, method=method)
        report['responses'].append(dict(label=label, status=status, body=body))
        persist()
        return status, body

    def stage(label, opener, level=None):
        filename = 'HT-INITIAL-API-' + uuid.uuid4().hex[:12] + '-' + label + '.md'
        raw = ('# 本地分类 API 验收\n\n这是保留的独立暂存测试原件。标识：' + filename + '\n').encode()
        boundary = 'initial-classification-' + uuid.uuid4().hex
        parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{filename}"\r\nContent-Type: text/markdown\r\n\r\n'.encode() + raw + b'\r\n']
        if level is not None:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="security_level"\r\n\r\n{level}\r\n'.encode())
        body = b''.join(parts) + f'--{boundary}--\r\n'.encode()
        req = urllib.request.Request(module.BASE + '/api/enterprise/batches', data=body,
                                     headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
        try:
            with opener.open(req, timeout=30) as response:
                status, value = response.status, json.load(response)
        except urllib.error.HTTPError as failure:
            status, value = failure.code, json.load(failure)
        report['responses'].append(dict(label='stage-' + label, status=status, body=value))
        if status == 201:
            report['retainedFixtures'].append(dict(batchId=value['batch']['batch_id'],
                fileId=value['batch']['files'][0]['file_id'], filename=filename,
                originalSha256=hashlib.sha256(raw).hexdigest(), originalBytes=len(raw)))
        persist()
        return status, value

    def snapshot():
        # The filter values are trusted local CLI names/owned fixture IDs, escaped as SQL literals.
        names = [args.logical_name, 'EKB-F01-财务登记', 'EKB-F02-财务登记']
        quoted = ','.join("'" + name.replace("'", "''") + "'" for name in names)
        sql = """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object('versions',(SELECT coalesce(jsonb_agg(t),'[]') FROM (
 SELECT d.document_id,d.logical_name,v.version_id,v.sha256,v.lifecycle,v.original_filename,
 m.security_level,m.acl_version,p.producer_scope,p.execution_epoch,p.acl_version AS published_acl_version
 FROM document d JOIN document_version v USING(document_id)
 JOIN document_metadata m USING(version_id) LEFT JOIN raw_publication p USING(version_id)
 WHERE d.logical_name IN (""" + quoted + """)) t),
 'files',(SELECT coalesce(jsonb_agg(t),'[]') FROM (SELECT file_id,batch_id,status,attempt,
 progress_stage,error_code,version_id,document_id,original_filename,requested_security_level
 FROM ingestion_file WHERE original_filename='HT-INITIAL-ACL-20261003保密验收.md') t));
COMMIT;
"""
        args.output.with_suffix('.sql').write_text(sql)
        result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt', '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'], input=sql, text=True, capture_output=True, timeout=30)
        args.output.with_suffix('.sql.log').write_text(result.stdout + result.stderr)
        result.check_returncode()
        return json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))

    def low_admin_counts(user_id, suffix):
        escaped = user_id.replace("'", "''")
        sql = "BEGIN READ ONLY; SELECT jsonb_build_object('sessions',(SELECT count(*) FROM kb_upload_session WHERE user_id='" + escaped + "'),'files',(SELECT count(*) FROM ingestion_file f JOIN ingestion_batch b USING(batch_id) WHERE b.created_by='" + escaped + "')); COMMIT;"
        result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt', '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'], input=sql, text=True, capture_output=True, timeout=30)
        args.output.with_suffix('.' + suffix + '.sql').write_text(sql)
        args.output.with_suffix('.' + suffix + '.log').write_text(result.stdout + result.stderr)
        result.check_returncode()
        return json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))

    persist()
    try:
        if args.early_clearance:
            low, low_opener = module.client(json.loads((ROOT / '.tmp/target/accounts.json').read_text())['admin'])
            status, me = observe('current-trusted-low-admin', low, '/auth/me')
            check('existingLowAdministratorHasTrustedClearanceTen', status == 200 and me['user']['role'] == 'ADMIN' and me['user']['security_clearance'] == 10)
            if not report['checks']['existingLowAdministratorHasTrustedClearanceTen']:
                raise ValueError('Do not mutate the account or fake the low-clearance precondition')
            count_before = low_admin_counts(me['user']['user_id'], 'early-before')
            create = dict(filename='HT-INITIAL-EARLY-' + uuid.uuid4().hex[:12] + '.md', size_bytes=1,
                          client_sha256=hashlib.sha256(b'x').hexdigest(), metadata=dict(logical_name='HT-INITIAL-EARLY-' + uuid.uuid4().hex[:12], manual_tags=[], security_level=30))
            status, rejection = observe('low-admin-early-multipart', low, '/upload-sessions', create, 'POST')
            check('lowAdminMultipartRejectedBeforeStorageOrSession', status == 403 and rejection.get('error') == 'source classification exceeds uploader clearance')
            status, rejection = stage('low-admin-early', low_opener, 30)
            check('lowAdminFormRejectedBeforeBatchOrSource', status == 403 and rejection.get('error') == 'source classification exceeds uploader clearance')
            check('bothRejectedRequestsCreateNoSessionOrFile', low_admin_counts(me['user']['user_id'], 'early-after') == count_before)
            status, value = stage('low-admin-owned-default', low_opener)
            check('sameLowAdministratorCanStageInheritedScope', status == 201 and value['batch']['files'][0].get('security_level') is None)
            owned = value['batch']['files'][0]
            status, rejection = observe('low-admin-early-own-patch', low, '/batch/file?id=' + owned['file_id'], {'security_level': 30}, 'PATCH')
            check('lowAdminOwnClassificationPatchRejectedBeforeRawAdmission', status == 403 and rejection.get('error') == 'source classification exceeds uploader clearance')
            status, actual = observe('low-admin-retained-staged-batch', low, '/batch?id=' + owned['batch_id'])
            saved = next(f for f in actual['files'] if f['file_id'] == owned['file_id'])
            check('rejectedOwnPatchLeavesStagedInheritanceAndZeroAttempt', status == 200 and saved['status'] == 'STAGED' and saved.get('security_level') is None and saved['attempt'] == 0 and not saved.get('version_id'))
        status, staged = stage('admin30', admin_opener, 30)
        check('normalFormFieldPersistsThirtyInsteadOfIgnoringIt', status == 201 and staged['batch']['files'][0]['security_level'] == 30)
        file = staged['batch']['files'][0]
        status, patch = observe('clear-admin-requested-classification', admin, '/batch/file?id=' + file['file_id'], {'clear_security_level': True}, 'PATCH')
        check('normalAdminClearRestoresInheritanceBeforeAdmission', status == 200 and patch['file'].get('security_level') is None and patch['file']['status'] == 'STAGED')
        status, patch = observe('set-admin-requested-classification', admin, '/batch/file?id=' + file['file_id'], {'security_level': 30}, 'PATCH')
        check('normalTypedPatchPersistsThirtyBeforeAdmission', status == 200 and patch['file']['security_level'] == 30)
        status, _ = observe('ambiguous-clear-plus-value', admin, '/batch/file?id=' + file['file_id'], {'security_level': 30, 'clear_security_level': True}, 'PATCH')
        check('ambiguousClearAndValueRejected', status == 400)
        for level in [9, 128]:
            status, _ = stage('invalid-' + str(level), admin_opener, level)
            check('invalidFormLevel' + str(level) + 'Rejected', status == 400)
        status, value = stage('admin-default', admin_opener)
        check('normalUnspecifiedClassificationIsRetainedAsInheritance', status == 201 and value['batch']['files'][0].get('security_level') is None)
        status, access = observe('actual-user-upload-policy', public, '/access')
        check('actualUserUploadPolicyReadable', status == 200)
        if access.get('can_upload'):
            status, value = stage('public-default', public_opener)
            check('enabledOrdinaryUploaderCanCreateUnclassifiedStagedFile', status == 201)
            own = value['batch']['files'][0]
            for label, payload in [('explicit-public', {'security_level': 10}), ('clear', {'clear_security_level': True})]:
                status, _ = observe('nonadmin-' + label, public, '/batch/file?id=' + own['file_id'], payload, 'PATCH')
                check('ordinaryUserCannot' + label + 'Classification', status == 403)
        else:
            status, _ = stage('disabled-public-default', public_opener)
            check('disabledOrdinaryUserUploadRejectedBeforeAdmission', status == 403)
            report['notTestedOnDeployedHttp'] = ['Classification-specific own-file USER patch: current global USER upload policy is disabled. Covered by isolated native 23-check regression; not confused with this global HTTP guard.']
        status, _ = stage('nonadmin-explicit-public', public_opener, 10)
        check('nonadminFormCannotAdmitExplicitClassification', status == 403)
        for label, api, level, expected in [('admin-invalid-low', admin, 9, 400), ('admin-invalid-high', admin, 128, 400), ('nonadmin-public', public, 10, 403)]:
            create = dict(filename='HT-INITIAL-INVALID-' + label + '.md', size_bytes=1,
                          client_sha256=hashlib.sha256(b'x').hexdigest(), metadata=dict(logical_name='HT-INITIAL-INVALID-' + label, manual_tags=[], security_level=level))
            status, _ = observe('multipart-' + label, api, '/upload-sessions', create, 'POST')
            check('multipart-' + label + '-rejected', status == expected)
        database = snapshot()
        report['nativeReadOnlySnapshot'] = database
        matching = [v for v in database['versions'] if v['logical_name'] == args.logical_name]
        check('browserSourceReachedActualActiveThirty', len(matching) == 1 and matching[0]['lifecycle'] == 'ACTIVE' and matching[0]['security_level'] == 30)
        for version in database['versions']:
            if version['lifecycle'] != 'ACTIVE':
                continue
            path = '/document/detail?version_id=' + version['version_id']
            status, _ = observe('admin-detail-' + version['logical_name'], admin, path)
            check('adminDetail-' + version['logical_name'], status == 200)
            status, blocked = observe('public-detail-' + version['logical_name'], public, path)
            check('publicDetail403-' + version['logical_name'], status == 403 and 'document' not in blocked)
        check('immutableDeploymentUnchangedDuringHttpAcceptance', json.loads((ROOT / '.tmp/target/process.json').read_text()) == process)
        report['status'] = 'PASS_DEPLOYED_INITIAL_CLASSIFICATION_HTTP' if all(report['checks'].values()) else 'FAIL_DEPLOYED_INITIAL_CLASSIFICATION_HTTP'
    except Exception as error:
        report.update(status='FAIL_DEPLOYED_INITIAL_CLASSIFICATION_HTTP', failureType=type(error).__name__, failure=str(error))
    report['finishedAt'] = datetime.now(timezone.utc).isoformat()
    persist()
    print(json.dumps(dict(status=report['status'], checksPassed=sum(report['checks'].values()), failed=[k for k,v in report['checks'].items() if not v], failure=report.get('failure')), ensure_ascii=False))
    raise SystemExit(not report['status'].startswith('PASS'))


if __name__ == '__main__':
    main()
