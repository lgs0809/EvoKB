"""Read-only retained-schema check of an actual own-JVM halt and DB600 recovery."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
p=argparse.ArgumentParser();p.add_argument('--before',type=Path,required=True);p.add_argument('--after',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise SystemExit('Fresh evidence required')
b=json.loads(a.before.read_text());r=json.loads(a.after.read_text());first=b['runs'][0]
file=first['fileId'];namespace=b['postgresSchemas'][first['scheme']]
if not re.fullmatch(r'raw_hashbench_[a-f0-9]+_(full|hash)',namespace) or not re.fullmatch(r'[a-f0-9-]{36}',file):raise SystemExit('Unsafe fixture identity')
q=f"""SET search_path TO {namespace};
SELECT json_build_object('file',row_to_json(f),'failures',(SELECT json_agg(x) FROM raw_ingestion_failure x WHERE x.file_id=f.file_id),
 'publication',(SELECT row_to_json(p) FROM raw_publication p WHERE p.version_id=f.version_id),
 'publicationMembers',(SELECT json_agg(pm) FROM raw_publication_member pm WHERE pm.version_id=f.version_id),
 'outbox',(SELECT row_to_json(o) FROM raw_ingestion_outbox o WHERE o.file_id=f.file_id)) FROM ingestion_file f WHERE file_id='{file}';"""
text=subprocess.check_output(['docker','exec','evokb-target-postgres-1','psql','-U','evokb_target','-d','evokb_target','-At','-c',q],text=True,timeout=20)
a.output.with_suffix('.native.sql').write_text(q)
a.output.with_suffix('.native.log').write_text(text)
rows=json.loads(text[text.index('{'):])
f=rows['file'];matching=[v for v in r.get('runs',[]) if v['scheme']==first['scheme'] and v['fixtureDocumentId']==first['fixtureDocumentId'] and v['changeRate']==first['changeRate']]
failures=rows['failures'] or [];pub=rows['publication'] or {}
republish=next((v for v in r['runs'] if v['scheme']==first['scheme'] and v['fixtureDocumentId']==first['fixtureDocumentId'] and v['changeRate']==0),{})
checks={
 'initialProcessActuallyHalted73':b.get('processExitCode')==73,
 'sameArtifactOriginalSchemasAndMilvusSpaces':b['jarSha256']==r['jarSha256'] and b['postgresSchemas']==r['postgresSchemas'] and b['milvusDatabases']==r['milvusDatabases'],
 'sameOriginalFileActuallySucceededSecondClaim':len(matching)==1 and matching[0]['fileId']==file and f['status']=='SUCCEEDED' and f['attempt']==2 and f['execution_epoch']==2,
 'originalExpiredAttemptPreserved':any(x['attempt']==1 and x['execution_epoch']==1 and x['error_code']=='RAW_STAGE_EXPIRED' and x['outcome']=='INTERRUPTED' for x in failures),
 'expiryRecoveryOccursAfterOriginalDeadline':all(datetime.fromisoformat(x['occurred_at'].replace('Z','+00:00'))>=datetime.fromisoformat(first['claimDeadline'].replace('Z','+00:00')) for x in failures if x['error_code']=='RAW_STAGE_EXPIRED'),
 'newDeadlineStillExactly600Seconds':(datetime.fromisoformat(f['stage_deadline'])-datetime.fromisoformat(f['stage_started_at'])).total_seconds()==600,
 'recoveredSecondEpochWasNormallyPublishedAndReadBackAtCompletion':len(matching)==1 and matching[0].get('claimScope')==file+':e2' and len(matching[0].get('physicalMilvusIds',[]))==3 and matching[0].get('checks',{}).get('actualOwnerGuardedTaskSucceeded') is True and matching[0].get('checks',{}).get('nativeCurrentSearchContainsEveryNewPhysicalChunkOnly') is True,
 'laterZeroPercentExactOriginalReparseNormallyReplacesCurrentProjection':republish.get('versionId')==matching[0].get('versionId') and republish.get('reusedExactOriginalVersion') is True and pub.get('file_id')==republish.get('fileId') and pub.get('producer_scope')==republish.get('claimScope') and set(v['physical_id'] for v in rows['publicationMembers'])==set(republish.get('physicalMilvusIds',[])),
 'threeRealPublishedPhysicalMembersRetained':len(rows['publicationMembers'] or [])==3,
 'firstAttemptAndNewAttemptBothRetainedByRunner':len([v for v in r.get('attemptRecords',[]) if v['scheme']==first['scheme'] and v['fixtureDocumentId']==first['fixtureDocumentId'] and v['changeRate']==first['changeRate']])==2,
 'wholeNativeSourceMatrixAfterRecoveryTwelvePass':r.get('executedSourceRuns')==12 and len(r.get('runs',[]))==12 and all(v.get('status')=='PASS' for v in r.get('runs',[])),
 'zeroActualModelCallsForMechanismProof':r.get('actualModelRequests')==0 and r.get('efficiencyClaim') is False,
}
report={'status':'PASS_ACTUAL600_HASH_RUNNER_RECOVERY_COMPONENT' if all(checks.values()) else 'FAIL_ACTUAL600_HASH_RUNNER_RECOVERY_COMPONENT',
 'checks':checks,'query':q,'actualReadAt':datetime.now(timezone.utc).isoformat(),'nativeRows':rows,'artifactSha256':b['jarSha256'],
 'beforeReportSha256':hashlib.sha256(a.before.read_bytes()).hexdigest(),'afterReportSha256':hashlib.sha256(a.after.read_bytes()).hexdigest(),
 'sourceCaseCount':12,'nativeAttemptCount':r.get('nativeAttemptExecutionCount'),'boundary':'Real PG/MinIO/Milvus and own independent JVM halt/recovery; synthetic vectors only, zero Qwen/Hash efficiency/ordinary API/browser claims. No schemas reseeded or task statuses edited.'}
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({'status':report['status'],'passed':sum(checks.values()),'failed':[k for k,v in checks.items() if not v],'artifactSha256':report['artifactSha256']}))
raise SystemExit(0 if all(checks.values()) else 1)
