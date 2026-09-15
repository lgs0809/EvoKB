"""Exact frozen-input counts and an explicitly non-guaranteed real-subset calibration."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics

p = argparse.ArgumentParser()
p.add_argument('--input-plan', type=Path, required=True)
p.add_argument('--calibration', type=Path, required=True)
p.add_argument('--batch-documents', type=int, default=5)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists() or not 1 <= a.batch_documents <= 100:
    raise SystemExit('Fresh output and 1..100 document batch required')
plan = json.loads(a.input_plan.read_text())
calibration = json.loads(a.calibration.read_text())
trace = [json.loads(line) for line in a.calibration.with_suffix('.http.jsonl').read_text().splitlines()]
if (plan['status'] != 'PASS_FROZEN_ACTUAL_HASH_INPUTS_NO_INFERENCE'
        or len(plan['documents']) != 100 or calibration['status'] != 'PASS_REAL_SUBSET_NOT_HASH100_COMPARISON'
        or calibration['jarSha256'] != plan['jarSha256']):
    raise SystemExit('Actual passed, artifact-matched frozen plan and subset required')

def quantile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)] if values else None

def distribution(values):
    return {'count': len(values), 'min': min(values), 'median': statistics.median(values),
            'p90': quantile(values, .90), 'p95': quantile(values, .95), 'max': max(values), 'sum': sum(values)}

rates = ['baseline', 0, 1, 5, 20, 100]
full_call_sizes, hash_call_sizes, source_sizes, chars, input_tokens = Counter(), Counter(), [], [], []
totals, snapshots = [], []
for rate in rates:
    full, changed, changed_documents, token_sum, changed_tokens = 0, 0, 0, 0, 0
    local_sizes = Counter()
    for document in plan['documents']:
        snapshot = document['baseline'] if rate == 'baseline' else next(v for v in document['versions'] if v['bodyUnitChangeRatePercent'] == rate)
        values = snapshot['inputs']
        for item in values:
            if hashlib.sha256(item['fullInput'].encode()).hexdigest() != item['fullInputSha256']:
                raise ValueError('Frozen input content/hash mismatch')
        changed_values = values if rate == 'baseline' else [v for v in values if v['changedFromBaseline']]
        full += len(values); changed += len(changed_values)
        token_sum += sum(v['tokenCount'] for v in values); changed_tokens += sum(v['tokenCount'] for v in changed_values)
        changed_documents += bool(changed_values)
        full_call_sizes[len(values)] += 1
        if changed_values:
            hash_call_sizes[len(changed_values)] += 1; local_sizes[len(changed_values)] += 1
        source_sizes.append(snapshot['source']['sizeBytes'])
        chars.extend(len(v['fullInput'].encode('utf-16-le')) // 2 for v in values)
        input_tokens.extend(v['tokenCount'] for v in values)
        snapshots.append({'documentId': document['documentId'], 'rate': rate,
                          'fullInputs': len(values), 'hashNewInputs': len(changed_values),
                          'fullTokens': sum(v['tokenCount'] for v in values),
                          'hashNewTokens': sum(v['tokenCount'] for v in changed_values)})
    totals.append({'rate': rate, 'documents': 100, 'fullInputs': full, 'hashNewInputs': changed,
                   'fullNativeTokens': token_sum, 'hashNewNativeTokens': changed_tokens,
                   'hashDocumentsNeedingInference': changed_documents,
                   'hashRequestInputCountHistogram': dict(local_sizes)})
latencies = {str(n): distribution([r['elapsedMillis'] for r in trace if r['attemptedInputCount'] == n and r.get('httpStatus') == 200])
             for n in sorted(set(r['attemptedInputCount'] for r in trace if r.get('httpStatus') == 200))}
overheads = [r['componentEndToEndSearchableMillis'] - r['encodingMillis'] for r in calibration['runs']]
batch_count = math.ceil(100 / a.batch_documents)
preflight_ms = calibration['encodingProfilePreflight']['elapsedMillis']
estimates = {}
for percentile in ('median', 'p90'):
    inference_ms = sum(count * latencies[str(size)][percentile] for size, count in (full_call_sizes + hash_call_sizes).items())
    overhead_ms = 1200 * distribution(overheads)[percentile]
    estimates[percentile] = {'inferenceSeconds': inference_ms / 1000, 'sourceComponentOverheadSeconds': overhead_ms / 1000,
                             'separatelyEstimatedBatchPreflightSeconds': batch_count * preflight_ms / 1000,
                             'estimatedHoursBeforeBatchInitialization': (inference_ms + overhead_ms + batch_count * preflight_ms) / 3600000}
batches = []
for offset in range(0, 100, a.batch_documents):
    documents = [d['documentId'] for d in plan['documents'][offset:offset + a.batch_documents]]
    local = [s for s in snapshots if s['documentId'] in documents]
    batches.append({'batchId': 'HASH100-BATCH-%02d' % (len(batches) + 1), 'documentIds': documents,
                    'globalDocumentIndexFrom': offset, 'nativeSourceRuns': len(documents) * 12,
                    'fullInputs': sum(s['fullInputs'] for s in local), 'hashNewInputs': sum(s['hashNewInputs'] for s in local),
                    'fullNativeTokens': sum(s['fullTokens'] for s in local), 'hashNewNativeTokens': sum(s['hashNewTokens'] for s in local)})
report = {'status': 'FROZEN_HASH100_PLAN_AND_NON_GUARANTEED_RUNTIME_ESTIMATE',
          'createdAt': datetime.now(timezone.utc).isoformat(), 'jarSha256': plan['jarSha256'],
          'inputPlanSha256': hashlib.sha256(a.input_plan.read_bytes()).hexdigest(),
          'calibrationSha256': hashlib.sha256(a.calibration.read_bytes()).hexdigest(),
          'calibrationHttpTraceSha256': hashlib.sha256(a.calibration.with_suffix('.http.jsonl').read_bytes()).hexdigest(),
          'atomicProfileSha256': calibration['atomicServingProfileSha256'], 'totalsByRate': totals,
          'plannedFullInputs': sum(v['fullInputs'] for v in totals), 'plannedHashInputs': sum(v['hashNewInputs'] for v in totals),
          'plannedFullTokens': sum(v['fullNativeTokens'] for v in totals), 'plannedHashTokens': sum(v['hashNewNativeTokens'] for v in totals),
          'plannedFullRequestCount': sum(full_call_sizes.values()), 'plannedHashRequestCount': sum(hash_call_sizes.values()),
          'originalSizeBytesDistributionAcross600Snapshots': distribution(source_sizes),
          'completeInputUtf16CharactersAcross1800Inputs': distribution(chars),
          'nativeInputTokensAcross1800Inputs': distribution(input_tokens),
          'observedHttpMillisByRequestedInputCount': latencies,
          'observedSourceOverheadMillis': distribution(overheads), 'estimatedRuntimeNotMeasuredPerformance': estimates,
          'batches': batches, 'proposedPerBatchOrchestratorLimitSeconds': 7200,
          'stageDeadlineSecondsUnchanged': 600, 'businessAttemptBudgetUnchanged': 5,
          'plannedExtraPreflightHttpCount': batch_count,
          'actualNewModelRequestsByEstimator': 0,
          'boundary': 'Counts are frozen planned inputs, never executed throughput. Runtime estimates use only two DEV documents with variable shared-host load, no confidence interval or completion guarantee. Entire process limit is test orchestration, not a changed task deadline/model/Gold. Batch/recovery wrapper not yet implemented by this estimator.'}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({key: report[key] for key in ('status', 'plannedFullInputs', 'plannedHashInputs', 'plannedFullTokens', 'plannedHashTokens', 'plannedFullRequestCount', 'plannedHashRequestCount', 'estimatedRuntimeNotMeasuredPerformance')}, ensure_ascii=False))
