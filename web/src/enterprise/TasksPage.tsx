import { useEffect, useState } from 'preact/hooks';
import { fetchEnterpriseTasks, retryEnterpriseBatchFile, type EnterpriseOverview, type EnterpriseTask } from '../lib/api';
import { EmptyState, Icon, LoadingBlock, MetricCard, SectionTitle, StatusBadge, formatDate, formatNumber, shortID } from './ui';

export function TasksPage({ overview, onError, isAdmin }: { overview: EnterpriseOverview | null; onError: (message: string) => void; isAdmin: boolean }) {
  const [tasks, setTasks] = useState<EnterpriseTask[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState('ALL');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState('');
  const pageSize = 30;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const pendingCount = isAdmin ? (overview?.pending_tasks || 0) : tasks.filter(item => ['STAGED', 'QUEUED', 'PENDING'].includes(item.status)).length;
  const runningCount = isAdmin ? (overview?.running_tasks || 0) : tasks.filter(item => item.status === 'RUNNING').length;
  const failedCount = isAdmin ? (overview?.failed_tasks || 0) : tasks.filter(item => item.status === 'FAILED').length;

  const load = async (nextPage = page, nextStatus = status) => {
    setLoading(true);
    try {
      const data = await fetchEnterpriseTasks({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, status: nextStatus });
      setTasks(data.tasks || []);
      setTotal(data.total || 0);
      setPage(nextPage);
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setLoading(false); }
  };

  useEffect(() => {
    void load(1, 'ALL');
    const timer = window.setInterval(() => {
      if (tasks.some(item => item.status === 'RUNNING' || item.status === 'QUEUED')) void load(page, status);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [page, status, tasks.some(item => item.status === 'RUNNING' || item.status === 'QUEUED')]);

  const retry = async (task: EnterpriseTask) => {
    if (!task.file_id) return;
    setRetrying(task.file_id);
    try { await retryEnterpriseBatchFile(task.file_id); await load(page, status); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setRetrying(''); }
  };

  return <div class="space-y-6">
    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="Pending / Staged" value={formatNumber(pendingCount)} hint={isAdmin ? '全局等待确认或执行' : '当前页我的上传任务'} icon="clock" tone="amber"/>
      <MetricCard label="Running" value={formatNumber(runningCount)} hint={isAdmin ? '全局解析 / Embedding / 索引' : '当前页我的运行任务'} icon="activity" tone="blue"/>
      <MetricCard label="Failed" value={formatNumber(failedCount)} hint="支持失败文件安全重试" icon="warning" tone="amber"/>
      <MetricCard label="Task history" value={formatNumber(total)} hint={isAdmin ? '全局 ingestion + compile' : '仅我的 ingestion'} icon="tasks" tone="violet"/>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle eyebrow="Ingest & compile pipeline" title="知识处理任务" description={isAdmin ? '统一展示全局文件入库与知识编译。文件入库进度来自真实后端阶段：STORING → PARSING → EVIDENCE → EMBEDDING → INDEXING → ACTIVATING。' : '这里只展示你自己上传的入库任务；不会暴露其他用户或系统 Summary / Wiki / Graph 编译任务。'} />
      <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div class="relative min-w-0 flex-1 lg:max-w-xl"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索文档、Task ID、Version、阶段或错误原因" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <div class="flex gap-2 overflow-x-auto">{['ALL','STAGED','QUEUED','PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED'].map(item => <button key={item} onClick={() => { setStatus(item); void load(1, item); }} class={`whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold ${status === item ? 'bg-slate-950 text-white dark:bg-white dark:text-slate-950' : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300'}`}>{item}</button>)}</div>
      </div>
    </section>

    <section class="space-y-3">
      {loading ? <LoadingBlock rows={7}/> : tasks.map(task => <article key={`${task.source}:${task.task_id}`} class="rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div class="grid gap-4 lg:grid-cols-[minmax(240px,1.2fr)_minmax(180px,.8fr)_160px_180px_auto] lg:items-center">
          <div class="flex min-w-0 items-start gap-3"><div class={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${task.status === 'FAILED' ? 'bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-300' : task.status === 'RUNNING' ? 'bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300' : 'bg-slate-100 text-slate-500 dark:bg-slate-800'}`}><Icon name={task.source === 'INGEST' ? 'documents' : task.status === 'FAILED' ? 'warning' : task.status === 'RUNNING' ? 'activity' : 'tasks'} size={18}/></div><div class="min-w-0"><div class="truncate text-sm font-semibold">{task.logical_name || task.task_type}</div><div class="mt-1 flex flex-wrap gap-2 text-[11px] text-slate-400"><span>{task.source || 'COMPILE'}</span><span>{task.task_type}</span><span>Task {shortID(task.task_id)}</span>{task.version_id && <span>Version {shortID(task.version_id)}</span>}</div></div></div>
          <div class="text-xs"><div class="font-medium text-slate-600 dark:text-slate-300">Attempt {task.attempt}</div><div class="mt-1 text-slate-400">Created {formatDate(task.created_at)}</div></div>
          <div><StatusBadge value={task.status}/><div class="mt-1 text-[11px] font-medium text-blue-500">{task.progress_stage || task.status}</div></div>
          <div class="text-xs text-slate-400"><div>Start {formatDate(task.started_at)}</div><div class="mt-1">Finish {formatDate(task.finished_at)}</div></div>
          <div>{task.source === 'INGEST' && task.status === 'FAILED' && task.file_id ? <button onClick={() => void retry(task)} disabled={retrying === task.file_id} class="rounded-lg border border-rose-200 px-3 py-1.5 text-xs font-semibold text-rose-600 hover:bg-rose-50 disabled:opacity-50 dark:border-rose-900">{retrying === task.file_id ? '排队中…' : '重试失败文件'}</button> : null}</div>
        </div>
        {task.error_message && <div class="mt-4 flex gap-2 rounded-xl border border-rose-100 bg-rose-50 px-3 py-2.5 text-xs leading-5 text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300"><Icon name="warning" size={14} class="mt-0.5 shrink-0"/><div><span class="font-semibold">{task.error_code || 'Task error'}:</span> {task.error_message}</div></div>}
      </article>)}
      {!loading && tasks.length === 0 && <EmptyState icon="tasks" title="没有匹配的知识处理任务" description="文件上传批次和 Summary / Graph / Wiki 编译任务会统一出现在这里。" />}
    </section>
    <div class="flex items-center justify-between rounded-xl border border-slate-200 bg-white px-4 py-3 text-xs text-slate-400 dark:border-slate-800 dark:bg-slate-900"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => void load(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => void load(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>
  </div>;
}
