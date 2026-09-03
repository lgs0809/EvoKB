import { useEffect, useState } from 'preact/hooks';
import { fetchEnterpriseTasks, retryEnterpriseGenerationTask, type EnterpriseTask } from '../lib/api';
import { EmptyState, Icon, LoadingBlock, SectionTitle, StatusBadge, formatDate, shortID } from './ui';

export function TasksPage({ onError }: { onError: (message: string) => void }) {
  const [tasks, setTasks] = useState<EnterpriseTask[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState('ALL');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState('');
  const pageSize = 30;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  const load = async (nextPage = page, nextStatus = status) => {
    setLoading(true);
    try {
      const data = await fetchEnterpriseTasks({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, status: nextStatus, source: 'COMPILE' });
      setTasks(data.tasks || []);
      setTotal(data.total || 0);
      setPage(nextPage);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setLoading(false);
    }
  };

  const retry = async (task: EnterpriseTask) => {
    setRetrying(task.task_id);
    try {
      await retryEnterpriseGenerationTask(task.task_id);
      await load(page, status);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setRetrying('');
    }
  };

  useEffect(() => {
    void load(1, 'ALL');
    const timer = window.setInterval(() => {
      if (tasks.some(item => item.status === 'RUNNING' || item.status === 'QUEUED')) void load(page, status);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [page, status, tasks.some(item => item.status === 'RUNNING' || item.status === 'QUEUED')]);

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle title="知识生成" />
      <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div class="relative min-w-0 flex-1 lg:max-w-xl"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索资料或生成记录" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <div class="flex gap-2 overflow-x-auto">{['ALL','QUEUED','PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED'].map(item => <button key={item} onClick={() => { setStatus(item); void load(1, item); }} class={`whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold transition ${status === item ? 'bg-blue-600 text-white shadow-sm shadow-blue-600/15 dark:bg-blue-500' : 'bg-slate-100 text-slate-500 hover:bg-blue-50 hover:text-blue-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-blue-950/30 dark:hover:text-blue-300'}`}>{statusLabel(item)}</button>)}</div>
      </div>
    </section>

    <section class="space-y-3">
      {loading ? <LoadingBlock rows={7}/> : tasks.map(task => <article key={task.task_id} class="rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div class="grid gap-4 lg:grid-cols-[minmax(260px,1.3fr)_180px_160px_180px] lg:items-center">
          <div class="flex min-w-0 items-start gap-3"><div class={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${task.status === 'FAILED' ? 'bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-300' : task.status === 'RUNNING' ? 'bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300' : 'bg-slate-100 text-slate-500 dark:bg-slate-800'}`}><Icon name={task.status === 'FAILED' ? 'warning' : task.status === 'RUNNING' ? 'activity' : 'sparkles'} size={18}/></div><div class="min-w-0"><div class="truncate text-sm font-semibold">{task.logical_name || '知识生成'}</div><div class="mt-1 flex flex-wrap gap-2 text-[11px] text-slate-400"><span>{generationLabel(task.task_type)}</span><span>记录 {shortID(task.task_id)}</span>{task.version_id && <span>版本 {shortID(task.version_id)}</span>}</div></div></div>
          <div class="text-xs"><div class="font-medium text-slate-600 dark:text-slate-300">第 {task.attempt} 次执行</div><div class="mt-1 text-slate-400">创建于 {formatDate(task.created_at)}</div></div>
          <div><StatusBadge value={task.status}/><div class="mt-1 text-[11px] font-medium text-blue-500">{generationStageLabel(task.progress_stage || task.status)}</div></div>
          <div class="text-xs text-slate-400"><div>开始 {formatDate(task.started_at)}</div><div class="mt-1">完成 {formatDate(task.finished_at)}</div></div>
        </div>
        {task.error_message && <div class="mt-4 flex gap-2 rounded-xl border border-rose-100 bg-rose-50 px-3 py-2.5 text-xs leading-5 text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300"><Icon name="warning" size={14} class="mt-0.5 shrink-0"/><div>{task.error_message}</div></div>}
        {task.source === 'COMPILE' && task.status === 'FAILED' && <div class="mt-3 flex justify-end"><button onClick={() => void retry(task)} disabled={retrying === task.task_id} class="rounded-xl border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-semibold text-blue-700 transition hover:border-blue-400 disabled:opacity-50 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-300">{retrying === task.task_id ? '重新排队中…' : '重试此阶段'}</button></div>}
      </article>)}
      {!loading && tasks.length === 0 && <EmptyState icon="sparkles" title="暂无知识生成记录" description="生成摘要、关系或企业百科后会显示在这里。" />}
    </section>
    <div class="flex items-center justify-between rounded-xl border border-slate-200 bg-white px-4 py-3 text-xs text-slate-400 dark:border-slate-800 dark:bg-slate-900"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => void load(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => void load(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>
  </div>;
}

function statusLabel(value: string) {
  const labels: Record<string, string> = { ALL: '全部', QUEUED: '排队中', PENDING: '等待处理', RUNNING: '生成中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' };
  return labels[value] || value;
}

function generationLabel(value: string) {
  const normalized = (value || '').toUpperCase();
  if (normalized.includes('SUMMARY')) return '摘要生成';
  if (normalized.includes('GRAPH') || normalized.includes('RELATION')) return '关系生成';
  if (normalized.includes('WIKI')) return '百科生成';
  if (normalized.includes('ENTITY')) return '实体归一';
  return '知识生成';
}

function generationStageLabel(value: string) {
  const labels: Record<string, string> = {
    QUEUED: '排队中', PENDING: '等待处理', RUNNING: '生成中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
    SUMMARY: '生成摘要', GRAPH: '生成关系', WIKI: '生成百科', ENTITY: '实体归一',
  };
  return labels[(value || '').toUpperCase()] || generationLabel(value);
}
