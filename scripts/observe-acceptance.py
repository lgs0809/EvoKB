#!/usr/bin/env python3
"""Observe actual local acceptance facts across API, SQL, object storage and search.

Read-only. Credentials and signed URLs are never included in the report.
This deliberately reports running/failed tasks without converting them to success.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:18480/api/enterprise'


def observe(output):
    if output.exists():
        raise ValueError('Use a new output path to retain earlier observations')
    account = json.loads((ROOT/'.tmp/acceptance/accounts.json').read_text())['admin']
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def api(path, data=None, binary=False):
        request = urllib.request.Request(BASE+path, data=None if data is None else json.dumps(data).encode(),
                                         headers={'Content-Type':'application/json'})
        with opener.open(request, timeout=30) as response:
            return response.read() if binary else json.load(response)

    api('/auth/login', account)
    statements = {
        'batches': "SELECT JSON_OBJECT('batchId',batch_id,'status',status,'createdBy',created_by) FROM ingestion_batch ORDER BY created_at;",
        'files': "SELECT JSON_OBJECT('fileId',file_id,'logicalName',logical_name,'versionId',version_id,'status',status,'stage',progress_stage,'attempt',attempt,'errorCode',error_code) FROM ingestion_file ORDER BY created_at,file_id;",
        'versions': "SELECT JSON_OBJECT('versionId',v.version_id,'documentId',v.document_id,'logicalName',d.logical_name,'versionNo',v.version_no,'lifecycle',v.lifecycle,'filename',v.original_filename,'sha256',v.sha256,'parser',v.parser_name,'parserVersion',v.parser_version) FROM document_version v JOIN document d ON d.document_id=v.document_id ORDER BY d.logical_name,v.version_no;",
        'tasks': "SELECT JSON_OBJECT('taskId',task_id,'versionId',version_id,'type',task_type,'subjectId',subject_id,'status',status,'attempt',attempt,'inputHash',input_hash,'outputHash',output_hash,'errorCode',error_code,'nextAttemptAt',next_attempt_at) FROM compile_task ORDER BY created_at,task_id;",
        'taskFailures': "SELECT JSON_OBJECT('taskId',task_id,'attempt',attempt,'outcome',outcome,'errorCode',error_code,'errorMessage',error_message,'occurredAt',occurred_at,'nextAttemptAt',next_attempt_at) FROM compile_task_failure ORDER BY task_id,attempt;",
        'turnEvents': "SELECT JSON_OBJECT('sessionId',session_id,'content',content,'createdAt',created_at) FROM query_turn WHERE role='SYSTEM_EVENT' ORDER BY created_at;",
        'evidence': "SELECT JSON_OBJECT('evidenceId',e.evidence_id,'versionId',e.version_id,'active',e.active,'contentHash',e.content_hash,'rawContent',b.raw_content) FROM evidence e JOIN raw_block b ON b.block_id=e.block_id ORDER BY e.evidence_id;",
        'summaries': "SELECT JSON_OBJECT('summaryId',summary_id,'versionId',version_id,'lifecycle',lifecycle,'grounding',grounding_status,'coverage',coverage_status,'model',model,'contentHash',content_hash) FROM summary ORDER BY created_at;",
        'sessions': "SELECT JSON_OBJECT('sessionId',session_id,'userId',user_id,'question',original_question,'status',status,'answer',final_answer,'evidenceOnly',evidence_only,'citations',citation_evidence_ids) FROM query_session ORDER BY created_at;",
        'rounds': "SELECT JSON_OBJECT('sessionId',session_id,'roundNo',round_no,'queries',input_queries,'decision',decision,'startedAt',started_at,'finishedAt',finished_at) FROM retrieval_round ORDER BY session_id,round_no;",
        'feedback': "SELECT JSON_OBJECT('feedbackId',feedback_id,'sessionId',session_id,'userId',user_id,'rating',rating,'category',category) FROM query_feedback ORDER BY created_at;",
        'searches': "SELECT JSON_OBJECT('searchId',search_id,'question',query_text,'mode',mode) FROM search_record ORDER BY created_at;",
    }
    result = {'status':'OBSERVED_ACTUAL_ACCEPTANCE_STATE', 'actualOperationTime':datetime.now(timezone.utc).isoformat(),
              'boundary':'Read-only synthetic acceptance data. Running/failed tasks are not passed. No formal QA accuracy assertion.'}
    for name, sql in statements.items():
        raw = subprocess.check_output(['docker','exec','-i','evokb-acceptance-mysql-1','sh','-c',
                                       'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -N -B --raw evokb_acceptance'],
                                      input=sql.encode(), timeout=30)
        result[name] = [json.loads(line) for line in raw.decode().splitlines() if line]
    result['apiOverview'] = api('/overview')
    result['apiSessionResponses'] = [api('/query/session?id='+s['sessionId']) for s in result['sessions'] if s['userId']==account['user_id']]
    original_files = []
    fixtures = ROOT/'deploy/acceptance/data'
    for version in result['versions']:
        matching = [e for e in result['evidence'] if e['versionId']==version['versionId']]
        if not matching:
            continue
        try:
            data = api('/evidence/source/file?id='+matching[0]['evidenceId'], binary=True)
        except urllib.error.HTTPError as error:
            original_files.append({'versionId':version['versionId'],'lifecycle':version['lifecycle'],'httpStatus':error.code,
                                   'matchesStoredHash':False,'matchesFixture':False,
                                   'expectedInactiveVersionDenied':version['lifecycle']!='ACTIVE' and error.code in [400,403,404]})
            continue
        file = fixtures/version['filename']
        original_files.append({'versionId':version['versionId'],'lifecycle':version['lifecycle'],'httpStatus':200,'bytes':len(data),'downloadSha256':hashlib.sha256(data).hexdigest(),
                               'matchesStoredHash':hashlib.sha256(data).hexdigest()==version['sha256'],
                               'matchesFixture':file.exists() and file.read_bytes()==data})
    result['actualOriginalFilesFromObjectStore'] = original_files
    search_url = 'http://127.0.0.1:19210/evokb_acceptance_raw_v1'
    with urllib.request.urlopen(search_url+'/_mapping', timeout=15) as response:
        mapping = json.load(response)
    result['rawIndexDimensions'] = mapping['evokb_acceptance_raw_v1']['mappings']['properties']['embedding']['dimension']
    with urllib.request.urlopen(search_url+'/_search?size=500', timeout=15) as response:
        hits = json.load(response)['hits']
    result['rawIndexTotal'] = hits['total']['value']
    result['rawIndexedChunks'] = [{'id':hit['_id'], 'versionId':hit['_source']['version_id'],
                                  'active':hit['_source']['is_active'], 'embeddingDimensions':len(hit['_source']['embedding']),
                                  'embeddingInputSha256':hashlib.sha256(hit['_source']['embedding_content'].encode()).hexdigest(),
                                  'evidenceIds':hit['_source']['evidence_ids']} for hit in hits['hits']]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    output.with_suffix('.sql').write_text('-- Read-only acceptance observations; no success state is written.\n'+'\n'.join(statements.values())+'\n')
    print(json.dumps({'status':result['status'],'versions':len(result['versions']),
                      'files':{f['fileId']:f['status'] for f in result['files']},
                      'originalFilesVerified':sum(f['matchesStoredHash'] and f['matchesFixture'] for f in original_files),
                      'rawIndexTotal':result['rawIndexTotal'], 'sessions':{s['sessionId']:s['status'] for s in result['sessions']},
                      'taskStatuses':{t['taskId']:t['status'] for t in result['tasks']}}, ensure_ascii=False))


if __name__=='__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    observe(p.parse_args().output)
