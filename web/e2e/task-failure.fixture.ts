import { h, render } from 'preact';
import type { EnterpriseTask } from '../src/lib/api';
import { GenerationFailure } from '../src/enterprise/GenerationFailure';
import { IngestionFailure } from '../src/enterprise/IngestionFailure';

// Synthetic presentation fixtures; these do not publish knowledge or prove model success.
const base: EnterpriseTask = {
  task_id: 'synthetic-task-1', task_type: 'SUMMARY', status: 'RETRY_WAIT', attempt: 3,
  created_at: '2026-10-07T10:00:00Z', next_attempt_at: '2026-10-07T10:10:00Z',
  error_code: 'SUMMARY_GENERATION_FAILED',
  error_message: 'grounding constraint audit: synthetic malformed candidate span',
};
for (const [id, task, isAdmin] of [
  ['waiting', base, true],
  ['persistent', { ...base, attempt: 12 }, true],
  ['running', { ...base, status: 'RUNNING', attempt: 4 }, true],
  ['exhausted', { ...base, status: 'FAILED', attempt: 5 }, true],
  ['retryable', { ...base, status: 'FAILED', attempt: 1 }, true],
  ['reader', base, false],
  ['expired', { ...base, status: 'FAILED', attempt: 5, error_code: 'STAGE_DEADLINE_EXPIRED' }, true],
  ['unknown', { ...base, task_type: 'FUTURE_STAGE', status: 'FAILED' }, true],
] as const) {
  render(h(GenerationFailure, { task, isAdmin }), document.getElementById(id)!);
}
render(h(IngestionFailure, {
  file: { file_id: 'synthetic-file-1', error_code: 'ORIGINAL_INTEGRITY_FAILED', error_message: 'synthetic storage diagnosis' },
  isAdmin: true,
}), document.getElementById('ingestion')!);

render(h(IngestionFailure, { file: {file_id:'synthetic-raw-wait',status:'QUEUED',attempt:12,next_attempt_at:base.next_attempt_at,error_code:'EMBEDDING_FAILED',error_message:'synthetic dependency'},isAdmin:false }),document.getElementById('ingestion-waiting')!);
