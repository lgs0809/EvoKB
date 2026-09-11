import type { EnterpriseIngestionFile } from '../lib/api';
import { FailureNotice } from './FailureNotice';
import { formatDate } from './ui';

type FailureCopy = { title: string; suggestion: string };

const invalidOriginal: FailureCopy = {
  title: '原件校验未通过',
  suggestion: '请核对文件是否完整、格式是否受支持，再重新选择原件上传。直接重新处理无法修复原件或校验信息。',
};
const configurationUnavailable: FailureCopy = {
  title: '入库服务配置暂未就绪',
  suggestion: '请联系管理员检查解析和检索配置，修复后再重新处理。',
};
const interrupted: FailureCopy = {
  title: '本次处理中断',
  suggestion: '请查看当前任务状态；仍为失败时，可稍后尝试重新处理。',
};
const failureCopy: Record<string, FailureCopy> = {
  INPUT_ERROR: invalidOriginal,
  ORIGINAL_INTEGRITY_FAILED: invalidOriginal,
  QUALITY_REVIEW_REQUIRED: { title: '原件解析质量需要检查', suggestion: '请检查原件是否清晰、完整，必要时换用可读性更好的文件再上传。' },
  TOKENIZER_UNAVAILABLE: configurationUnavailable,
  ENCODING_PROFILE_MISMATCH: configurationUnavailable,
  PARSER_PROFILE_INVALID: configurationUnavailable,
  PARSER_VERSION_MISMATCH: configurationUnavailable,
  PARSER_IDENTITY_UNAVAILABLE: configurationUnavailable,
  PARSER_IDENTITY_CHANGED: { title: '需要重新解析', suggestion: '原任务解析身份缺失或不匹配，不能按旧记录继续发布。请以当前配置重新解析保留原件；原件、历史版本与失败记录都会保留。' },
  PARSER_CHECKPOINT_UNVERIFIED: { title: '保留的解析内容无法确认', suggestion: '本次没有复用未经确认的内容。请查看原任务与当前解析规则，联系管理员检查原件和解析记录。' },
  PARSER_CHECKPOINT_INVALID: configurationUnavailable,
  PARSER_RESULT_STALE: { title: '旧解析结果已失效', suggestion: '原结果记录会保留，后台会按退避时间重新请求当前解析服务，原失败记录保留。' },
  RAW_SOURCE_REVOKED: { title: '资料版本或访问权限已变化', suggestion: '请到资料管理查看最新版本和权限。需要继续处理时，请联系管理员确认。' },
  STAGING_FILE_MISSING: { title: '暂存原件无法读取', suggestion: '请重新选择原件上传；需要保留当前任务时，请联系管理员检查原件存储。' },
  ORIGINAL_RESTORE_FAILED: { title: '原件读取未完成', suggestion: '请稍后尝试重新处理；仍无法读取时，请联系管理员检查原件存储。' },
  PARSER_FAILED: { title: '资料解析未完成', suggestion: '请检查原件格式和内容；稍后仍无法处理时，请联系管理员。' },
  EMBEDDING_FAILED: { title: '检索内容准备未完成', suggestion: '请稍后尝试重新处理；仍然失败时，请联系管理员检查模型服务。' },
  SEARCH_INDEX_FAILED: { title: '检索索引建立未完成', suggestion: '请稍后尝试重新处理；仍然失败时，请联系管理员检查检索服务。' },
  RAW_STAGE_EXPIRED: interrupted,
  RAW_STALE_CLAIM: interrupted,
  RAW_SOURCE_BUSY: { title: '同一资料正在处理中', suggestion: '请先查看资料的其他入库记录，等待当前处理结束后再尝试。' },
  PARSER_STAGE_TIMEOUT: interrupted,
  PARSER_PROCESS_INTERRUPTED: interrupted,
  PARSER_WORKER_LOST: interrupted,
  TASK_PERSIST_FAILED: interrupted,
};

export function IngestionFailure({ file, isAdmin }: {
  file: Pick<EnterpriseIngestionFile, 'file_id' | 'error_code' | 'error_message'> & Partial<Pick<EnterpriseIngestionFile, 'status' | 'attempt' | 'next_attempt_at'>>;
  isAdmin: boolean;
}) {
  if (file.status === 'QUEUED' && file.next_attempt_at) return <FailureNotice title="入库等待自动重试"
    suggestion={`已执行 ${file.attempt || 0} 次，系统最早于 ${formatDate(file.next_attempt_at)} 后继续处理；失败后会继续退避重试，历史记录保留。`}
    recordId={file.file_id} errorCode={file.error_code} diagnostic={file.error_message} showDiagnostic={isAdmin} waiting/>;
  if (!file.error_code && !file.error_message) return null;
  const code = (file.error_code || '').toUpperCase();
  const copy = failureCopy[code] || {
    title: '入库未完成',
    suggestion: '请联系管理员检查本次处理记录，确认原因后再重新处理。',
  };
  return <FailureNotice title={copy.title} suggestion={copy.suggestion} recordId={file.file_id}
    errorCode={code} diagnostic={file.error_message} showDiagnostic={isAdmin}/>;
}
