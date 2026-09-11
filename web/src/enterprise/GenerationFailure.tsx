import type { EnterpriseTask } from '../lib/api';
import { FailureNotice } from './FailureNotice';
import { formatDate } from './ui';

const stages: Record<string, string> = {
  SUMMARY: '摘要生成', CONCEPT: '概念提炼', GRAPH: '关系生成', WIKI: '百科生成',
  SOURCE_ASSOCIATION: '关联资料识别', EMBEDDING_REBUILD: '检索内容更新',
};

export function GenerationFailure({ task, isAdmin }: { task: EnterpriseTask; isAdmin: boolean }) {
  const waiting = task.status === 'RETRY_WAIT';
  if (!waiting && task.status !== 'FAILED') return null;
  const stage = stages[task.task_type] || '知识生成';
  let suggestion: string;
  if (waiting) {
    suggestion = `第 ${task.attempt} 次尝试未完成，系统最早于 ${formatDate(task.next_attempt_at)} 后自动重试；失败后会继续退避重试。当前记录已保留，你可以继续使用其他资料。`;
  } else {
    suggestion = '本次处理未完成，失败记录已保留。可以重试当前阶段；若反复失败，请检查模型服务、原文依据和当前资料权限。';
  }
  if (task.error_code === 'STAGE_DEADLINE_EXPIRED') {
    suggestion = `本次处理达到阶段时限，已停止这次尝试。${suggestion}`;
  }
  return <FailureNotice title={waiting ? `${stage}等待自动重试` : `${stage}未完成`} suggestion={suggestion}
    recordId={task.task_id} errorCode={task.error_code} diagnostic={task.error_message} showDiagnostic={isAdmin} waiting={waiting}/>;
}
