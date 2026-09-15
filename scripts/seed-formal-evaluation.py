#!/usr/bin/env python3
"""Import frozen synthetic dossiers through ordinary staged APIs, preserving every existing row."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--family', action='append', default=[])
    parser.add_argument('--filename', action='append', default=[],
                        help='Exact frozen filename; repeat to resume only selected untouched sources')
    parser.add_argument('--observe-seconds', type=int, default=3600)
    parser.add_argument('--stop-after-current-source-file', type=Path,
                        help='If this marker exists, stop only between sources; never cancel an admitted task')
    args = parser.parse_args()
    if not 600 <= args.observe_seconds <= 3600:
        raise ValueError('Observation budget only; never modifies the 600-second stage or five attempts')
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = json.loads(args.manifest.read_text())
    material_root = args.manifest.resolve().parent
    documents = [document for document in manifest['documents']
                 if (not args.family or document['family'] in args.family)
                 and (not args.filename or document['filename'] in args.filename)]
    if not documents or any(family not in {document['family'] for document in documents} for family in args.family):
        raise ValueError('Unknown or empty frozen family selection')
    if args.filename and set(args.filename) != {document['filename'] for document in documents}:
        raise ValueError('Every selected filename must match an exact frozen source in the selected families')
    for document in documents:
        path = (material_root / document['path']).resolve()
        if not path.is_relative_to(material_root) or hashlib.sha256(path.read_bytes()).hexdigest() != document['sha256']:
            raise ValueError('Frozen original bytes changed or escaped the fixture directory')
    process = json.loads((ROOT / '.tmp/target/process.json').read_text())
    if hashlib.sha256(Path(process['artifact']).read_bytes()).hexdigest() != process['jarSha256']:
        raise ValueError('Immutable deployed artifact changed')
    spec = importlib.util.spec_from_file_location('permission_client', ROOT / 'scripts/test-target-permissions.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    account = json.loads((ROOT / '.tmp/target/permission-accounts.json').read_text())['admin']
    request, opener = module.client(account)
    profile_status, profile_view = request('/model-configurations')
    active = next(profile for profile in profile_view['profiles'] if profile['active'])
    if profile_status != 200 or active['embedding_base_url'] != 'http://127.0.0.1:8110' or active['small_model'] != 'gpt-5.6-luna' or active['large_model'] != 'gpt-5.6-terra':
        raise ValueError('Restore the approved normal profile before importing')
    report = dict(status='RUNNING_NORMAL_API_CORPUS_IMPORT', startedAt=now(), process=process,
                  manifestSha256=hashlib.sha256(args.manifest.read_bytes()).hexdigest(), runs=[],
                  plannedDocuments=len(documents), checks={},
                  scope='Synthetic source import only. Staged metadata and ACL are saved before ordinary '
                        'batch/start. All SQL is read-only. No query, download, heat, publication, parser '
                        'success, model output or task status is manufactured. Failed and exhausted tasks '
                        'are preserved; this script never resets attempt counters or replaces content.')
    if args.stop_after_current_source_file:
        report['stopAfterCurrentSourceFile'] = str(args.stop_after_current_source_file.resolve())

    def persist():
        (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    def snapshot(label):
        sql = """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'versions',(SELECT coalesce(jsonb_agg(v),'[]') FROM (SELECT d.document_id,d.logical_name,
   v.version_id,v.sha256,v.lifecycle,v.original_filename,m.security_level,m.acl_version
   FROM document d JOIN document_version v USING(document_id) JOIN document_metadata m USING(version_id)) v),
 'files',(SELECT coalesce(jsonb_agg(f),'[]') FROM (SELECT file_id,batch_id,original_filename,
   logical_name,sha256,status,attempt,version_id,document_id FROM ingestion_file) f));
COMMIT;
"""
        (output / (label + '.sql')).write_text(sql)
        result = subprocess.run(['docker', 'exec', '-i', 'evokb-target-postgres-1', 'psql', '-XAt',
                                 '-U', 'evokb_target', '-d', 'evokb_target', '-v', 'ON_ERROR_STOP=1'],
                                input=sql, text=True, capture_output=True, timeout=30)
        (output / (label + '.log')).write_text(result.stdout + result.stderr)
        result.check_returncode()
        return json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))

    before = snapshot('before')
    report['before'] = before
    # Reject every collision before the first mutation, including earlier partially staged attempts.
    for document in documents:
        versions = [row for row in before['versions'] if row['logical_name'] == document['logicalName']]
        files = [row for row in before['files'] if row['original_filename'] == document['filename']]
        if any(row['sha256'] != document['sha256'] for row in versions + files):
            raise ValueError('Original identity collision; all current business content preserved')
        if len(versions) > 1 or len(files) > 1:
            raise ValueError('Ambiguous prior import; inspect it instead of creating another attempt')
        if versions and versions[0]['security_level'] != document['securityLevel']:
            raise ValueError('Existing source authority differs; do not overwrite it')
    persist()
    try:
        stopped_at_boundary = False
        for document in documents:
            if args.stop_after_current_source_file and args.stop_after_current_source_file.exists():
                stopped_at_boundary = True
                report['notStartedFilenames'] = [item['filename'] for item in documents[len(report['runs']):]]
                persist()
                break
            if json.loads((ROOT / '.tmp/target/process.json').read_text()) != process:
                raise ValueError('Deployment changed; preserve partial import and rebind a new attempt')
            run = dict(filename=document['filename'], originalSha256=document['sha256'], startedAt=now(), observations=[])
            report['runs'].append(run)
            persist()
            existing = [row for row in before['files'] if row['original_filename'] == document['filename']]
            if existing:
                file_id, batch_id = existing[0]['file_id'], existing[0]['batch_id']
                run['reusedStagedIdentity'] = True
                code, batch = request('/batch?id=' + batch_id)
                if code != 200:
                    raise ValueError('Existing normal batch is not readable')
                file = next(file for file in batch['files'] if file['file_id'] == file_id)
                if file['status'] not in ['STAGED', 'QUEUED', 'RUNNING', 'SUCCEEDED']:
                    raise ValueError('Prior failed task preserved; use its normal authorized recovery policy')
            else:
                raw = (material_root / document['path']).read_bytes()
                boundary = 'evokb-formal-' + uuid.uuid4().hex
                body = (f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{document["filename"]}"\r\nContent-Type: text/markdown; charset=utf-8\r\n\r\n').encode() + raw + f'\r\n--{boundary}--\r\n'.encode()
                upload = urllib.request.Request(module.BASE + '/api/enterprise/batches', data=body,
                                                headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
                with opener.open(upload, timeout=30) as response:
                    code, batch = response.status, json.load(response)['batch']
                if code != 201 or len(batch['files']) != 1:
                    raise ValueError('Normal staged upload failed')
                file_id, batch_id = batch['files'][0]['file_id'], batch['batch_id']
                file = batch['files'][0]
                run['reusedStagedIdentity'] = False
            run.update(fileId=file_id, batchId=batch_id)
            persist()
            if file['status'] == 'STAGED':
                code, metadata_response = request('/batch/file?id=' + file_id,
                    dict(logical_name=document['logicalName'], source_department='联测合成',
                         material_category='合同尽调', document_type=document['kind'],
                         manual_tags=['EvoKB-formal-v1', document['family'], document['split']],
                         security_level=document['securityLevel']), method='PATCH')
                if code != 200:
                    raise ValueError('Normal staged metadata/authority patch failed')
                # An older typed DTO accepted the request but silently ignored security_level.
                # Read back the ordinary representation before admitting any Raw/model work.
                saved = metadata_response.get('file', {})
                run['initialClassificationReadback'] = dict(
                    requested=document['securityLevel'], returned=saved.get('security_level'),
                    status=saved.get('status'), fileId=saved.get('file_id'))
                persist()
                if (saved.get('file_id') != file_id or saved.get('status') != 'STAGED'
                        or saved.get('security_level') != document['securityLevel']):
                    raise ValueError('Initial classification was not persisted; batch/start is not authorized')
                code, _ = request('/batch/start', dict(batch_id=batch_id))
                if code != 200:
                    raise ValueError('Normal batch/start failed')
            deadline, previous = time.monotonic() + args.observe_seconds, None
            while time.monotonic() < deadline:
                code, batch = request('/batch?id=' + batch_id)
                if code != 200:
                    raise ValueError('Normal batch polling failed')
                file = next(file for file in batch['files'] if file['file_id'] == file_id)
                observed = {key: file.get(key) for key in ['status', 'progress_stage', 'attempt',
                            'version_id', 'document_id', 'error_code', 'started_at', 'finished_at']}
                if observed != previous:
                    run['observations'].append(dict(observedAt=now(), **observed))
                    previous = observed
                    persist()
                    print(json.dumps(dict(filename=document['filename'], **observed)), flush=True)
                if file['status'] in ['SUCCEEDED', 'FAILED']:
                    run.update(finishedAt=now(), final=observed)
                    persist()
                    if file['status'] != 'SUCCEEDED':
                        raise ValueError('Actual Raw failed; no corpus success or replacement is created')
                    current = snapshot('source-' + file_id)
                    matching = [row for row in current['versions']
                                if row['version_id'] == file.get('version_id')]
                    run['completedSourceReadback'] = matching
                    persist()
                    if (len(matching) != 1 or matching[0]['sha256'] != document['sha256']
                            or matching[0]['original_filename'] != document['filename']
                            or matching[0]['logical_name'] != document['logicalName']
                            or matching[0]['lifecycle'] != 'ACTIVE'
                            or matching[0]['security_level'] != document['securityLevel']):
                        raise ValueError('Completed Raw does not match frozen identity/classification; stop before the next source')
                    break
                time.sleep(5)
            else:
                raise TimeoutError('Observation expired; actual task is retained, not cancelled or marked successful')
        after = snapshot('after')
        report['after'] = after
        own_names = {document['logicalName'] for document in documents}
        untouched_before = [row for row in before['versions'] if row['logical_name'] not in own_names]
        checks = report['checks']
        checks['allPreexistingOtherBusinessVersionsAndAuthorityPreserved'] = all(row in after['versions'] for row in untouched_before)
        checks['exactlyOneSuccessfulStagedIdentityPerFrozenOriginal'] = all(
            len([row for row in after['files'] if row['original_filename'] == document['filename'] and row['sha256'] == document['sha256'] and row['status'] == 'SUCCEEDED']) == 1 for document in documents)
        checks['eachFrozenOriginalHasOneActiveExactVersionAndAuthority'] = all(
            len([row for row in after['versions'] if row['logical_name'] == document['logicalName'] and row['sha256'] == document['sha256'] and row['lifecycle'] == 'ACTIVE' and row['security_level'] == document['securityLevel']]) == 1 for document in documents)
        checks['everyNormalTaskReachedActualSucceeded'] = len(report['runs']) == len(documents) and all(run['final']['status'] == 'SUCCEEDED' for run in report['runs'])
        if stopped_at_boundary:
            report['status'] = 'STOPPED_AT_SAFE_SOURCE_BOUNDARY'
        else:
            report['status'] = 'PASS_NORMAL_CORPUS_SOURCE_IMPORT' if all(checks.values()) else 'FAIL_NORMAL_CORPUS_SOURCE_IMPORT'
    except Exception as failure:
        report.update(status='FAIL_OR_PARTIAL_NORMAL_CORPUS_SOURCE_IMPORT',
                      failureType=type(failure).__name__, failure=str(failure))
    report['finishedAt'] = now()
    persist()
    print(json.dumps(dict(status=report['status'], plannedDocuments=len(documents),
                         actualRuns=len(report['runs']), checks=report['checks'])), flush=True)
    raise SystemExit(2 if report['status'] == 'STOPPED_AT_SAFE_SOURCE_BOUNDARY'
                     else int(not report['status'].startswith('PASS')))


if __name__ == '__main__':
    main()
