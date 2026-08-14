import { useMemo, useState } from 'preact/hooks';
import type { EnterpriseOverview, EnterpriseTask } from '../lib/api';
import { EmptyState, Icon, MetricCard, SectionTitle, StatusBadge, formatDate, formatNumber, shortID } from './ui';

export function TasksPage({ tasks, overview }: { tasks: EnterpriseTask[]; overview: EnterpriseOverview | null }) {
  const [status, setStatus] = useState('ALL');
  const [query, setQuery] = useState('');
  const filtered = useMemo(() => tasks.filter(task => {
    const matchesStatus = status === 'ALL' || task.status === status;
    const needle = query.trim().toLowerCase();
    const matchesQuery = !needle || [task.logical_name, task.task_type, task.task_id, task.version_id, task.error_message].some(value => value?.toLowerCase().includes(needle));
    return matchesStatus && matchesQuery;
  }), [tasks, status, query]);

  return <div class="space-y-6">
    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="Pending" value={formatNumber(overview?.pending_tasks)} hint="等待执行" icon="clock" tone="amber"/>
      <MetricCard label="Running" value={formatNumber(overview?.running_tasks)} hint="正在执行" icon="activity" tone="blue"/>
      <MetricCard label="Failed" value={formatNumber(overview?.failed_tasks)} hint="需要定位原因" icon="warning" tone="amber"/>
      <MetricCard label="Task history" value={formatNumber(tasks.length)} hint="当前加载记录" icon="tasks" tone="violet"/>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle eyebrow="Compile pipeline" title="知识编译任务" description="任务状态直接来自 compile_task。当前页面只做透明观察，不在没有明确幂等语义的情况下提供危险的“一键重跑”。" />
      <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div class="relative min-w-0 flex-1 lg:max-w-xl"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} placeholder="搜索文档、Task ID、Version 或错误原因" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <div class="flex gap-2 overflow-x-auto">{['ALL', 'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].map(item => <button key={item} onClick={() => setStatus(item)} class={`whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold ${status === item ? 'bg-slate-950 text-white dark:bg-white dark:text-slate-950' : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300'}`}>{item}</button>)}</div>
      </div>
    </section>

    <section class="space-y-3">
      {filtered.map(task => <article key={task.task_id} class="rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div class="grid gap-4 lg:grid-cols-[minmax(240px,1.2fr)_minmax(180px,.8fr)_140px_160px] lg:items-center">
          <div class="flex min-w-0 items-start gap-3"><div class={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${task.status === 'FAILED' ? 'bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-300' : task.status === 'RUNNING' ? 'bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300' : 'bg-slate-100 text-slate-500 dark:bg-slate-800'}`}><Icon name={task.status === 'FAILED' ? 'warning' : task.status === 'RUNNING' ? 'activity' : 'tasks'} size={18}/></div><div class="min-w-0"><div class="truncate text-sm font-semibold">{task.logical_name || task.task_type}</div><div class="mt-1 flex flex-wrap gap-2 text-[11px] text-slate-400"><span>{task.task_type}</span><span>Task {shortID(task.task_id)}</span>{task.version_id && <span>Version {shortID(task.version_id)}</span>}</div></div></div>
          <div class="text-xs"><div class="font-medium text-slate-600 dark:text-slate-300">Attempt {task.attempt}</div><div class="mt-1 text-slate-400">Created {formatDate(task.created_at)}</div></div>
          <div><StatusBadge value={task.status}/></div>
          <div class="text-xs text-slate-400"><div>Start {formatDate(task.started_at)}</div><div class="mt-1">Finish {formatDate(task.finished_at)}</div></div>
        </div>
        {task.error_message && <div class="mt-4 flex gap-2 rounded-xl border border-rose-100 bg-rose-50 px-3 py-2.5 text-xs leading-5 text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300"><Icon name="warning" size={14} class="mt-0.5 shrink-0"/><div><span class="font-semibold">{task.error_code || 'Task error'}:</span> {task.error_message}</div></div>}
      </article>)}
      {filtered.length === 0 && <EmptyState icon="tasks" title="没有匹配的编译任务" description="新文档解析、Summary / Graph / Wiki 编译任务创建后会出现在这里。" />}
    </section>
  </div>;
}
