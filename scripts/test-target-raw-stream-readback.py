#!/usr/bin/env python3
"""Read retained normal Raw facts and exact Redis delivery/PEL identities; no writes."""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--file-id', action='append', required=True)
p.add_argument('--jar-sha256', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    raise SystemExit('Fresh output required; preserve prior results')
if not all(re.fullmatch('[0-9a-f-]{36}', value) for value in a.file_id):
    raise SystemExit('Canonical file IDs required')
state_path = REPO / '.tmp/target/process.json'
state = json.loads(state_path.read_text())
if state['jarSha256'] != a.jar_sha256:
    raise SystemExit('Binding must match current deployed immutable artifact')
report = {
    'status': 'RUNNING_NORMAL_RAW_STREAM_READBACK',
    'deployedObserverArtifact': state,
    'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'boundary': 'Read-only normal public PG and native Redis. Existing browser-upload tasks executed under their original a2d6/28ff artifacts; this current deployed observer records retained outbox/history, full publication identity and absence of exact message IDs from PEL. It performs no new claim, ACK, parse, model call or restart.',
    'checks': {}, 'observations': [],
    'stream': 'evokb:target:raw:v1', 'consumerGroup': 'raw-ingestion',
}


def command(parts):
    result = subprocess.run(parts, capture_output=True, text=True, timeout=15)
    result.check_returncode()
    return result.stdout.strip()


try:
    ids = ','.join("'" + value + "'" for value in a.file_id)
    sql = (
        "SELECT json_build_object('fileId',f.file_id,'status',f.status,'attempt',f.attempt,"
        "'executionEpoch',f.execution_epoch,'stage',f.progress_stage,'versionId',f.version_id,"
        "'messageId',o.message_id,'deliveryEpoch',o.delivery_epoch,'dispatchedAt',o.dispatched_at,"
        "'exactDeliveryHistoryCount',(SELECT count(*) FROM raw_ingestion_delivery_history h WHERE h.file_id=f.file_id AND h.message_id=o.message_id AND h.delivery_epoch=o.delivery_epoch),"
        "'publicationFileId',r.file_id,'publicationVersionId',r.version_id,"
        "'publicationEpoch',r.execution_epoch,'producerScope',r.producer_scope,"
        "'currentSource',s.version_id IS NOT NULL)::text FROM ingestion_file f "
        "LEFT JOIN raw_ingestion_outbox o ON o.file_id=f.file_id "
        "LEFT JOIN raw_publication r ON r.version_id=f.version_id "
        "LEFT JOIN kb_source_authority s ON s.version_id=f.version_id "
        "WHERE f.file_id IN (" + ids + ");"
    )
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.with_suffix('.readback.sql').write_text(sql)
    rows = [json.loads(line) for line in command([
        'docker', 'exec', 'evokb-target-postgres-1', 'psql', '-U', 'evokb_target',
        '-d', 'evokb_target', '-At', '-c', sql,
    ]).splitlines() if line]
    assert {row['fileId'] for row in rows} == set(a.file_id), 'All requested normal files must exist'
    for row in rows:
        message = row['messageId']
        assert re.fullmatch('[0-9]+-[0-9]+', message or ''), 'A recorded native message ID is required'
        base = ['docker', 'exec', 'evokb-target-redis-1', 'redis-cli', '--json']
        body = json.loads(command(base + ['XRANGE', report['stream'], message, message]))
        pending = json.loads(command(base + ['XPENDING', report['stream'], report['consumerGroup'], message, message, '1']))
        field = dict(zip(body[0][1][::2], body[0][1][1::2])) if len(body) == 1 else {}
        key = row['fileId'] + ':'
        checks = report['checks']
        checks[key + 'normalFileRetainsSucceededTerminalFact'] = row['status'] == 'SUCCEEDED' and row['stage'] == 'SUCCEEDED'
        checks[key + 'outboxAndExactHistoryRetainSameDeliveryIdentity'] = row['dispatchedAt'] is not None and row['exactDeliveryHistoryCount'] == 1
        checks[key + 'nativeStreamRetainsExactFileBody'] = len(body) == 1 and body[0][0] == message and field == {'file_id': row['fileId']}
        checks[key + 'terminalMessageNoLongerPending'] = pending == []
        checks[key + 'currentPublicationStillOwnedByActualFileVersion'] = row['publicationFileId'] == row['fileId'] and row['publicationVersionId'] == row['versionId'] and row['currentSource'] is True
        report['observations'].append({**row, 'retainedStreamBody': body, 'exactPendingEntries': pending})
    after = json.loads(state_path.read_text())
    report['checks']['observerArtifactUnchangedDuringReadback'] = after['jarSha256'] == state['jarSha256'] and after['pid'] == state['pid']
    report['status'] = 'PASS_NORMAL_RAW_STREAM_READBACK' if all(report['checks'].values()) else 'FAIL_NORMAL_RAW_STREAM_READBACK'
except Exception as error:
    report.update(status='FAIL_NORMAL_RAW_STREAM_READBACK', failure=type(error).__name__ + ': ' + str(error))
report.update(finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({'status': report['status'], 'passed': sum(report['checks'].values()), 'failed': [key for key, value in report['checks'].items() if not value], 'output': str(a.output)}, ensure_ascii=False))
raise SystemExit(0 if report['status'].startswith('PASS') else 1)
