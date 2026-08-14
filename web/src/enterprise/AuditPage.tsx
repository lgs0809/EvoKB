import { useEffect, useMemo, useState } from 'preact/hooks';
import { fetchEnterpriseAudit, type EnterpriseAuditEvent } from '../lib/api';
import { EmptyState, Icon, LoadingBlock, SectionTitle, StatusBadge, formatDate, shortID } from './ui';

const actionLabels: Record<string, string> = {
  'auth.setup': '初始化管理员',
  'auth.login': '登录',
  'auth.logout': '退出登录',
  'auth.password.change': '修改本人密码',
  'user.create': '创建用户',
  'user.update': '更新用户',
  'user.password.reset': '重置用户密码',
  'access.user_upload.update': '修改 USER 上传权限',
  'document.upload.create': '单文件上传',
  'document.batch.create': '创建上传批次',
  'document.batch.file.update': '调整文件属性',
  'document.batch.start': '启动批量入库',
  'document.ingest.retry': '重试文件入库',
  'document.distill.mark': '标记高价值 Source',
  'knowledge.ask': '企业问答',
  'source_package.create': '打包来源',
  'evidence.source.open': '打开原始来源',
};

export function AuditPage({ onError }: { onError: (message: string) => void }) {
  const [events, setEvents] = useState<EnterpriseAuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState('');
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<EnterpriseAuditEvent | null>(null);
  const pageSize = 30;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  const load = async (nextPage = page) => {
    setLoading(true);
    try {
      const data = await fetchEnterpriseAudit({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, actor: actor.trim() || undefined, action: action || undefined, outcome: outcome === 'ALL' ? undefined : outcome });
      setEvents(data.events || []);
      setTotal(data.total || 0);
      setPage(nextPage);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(1); }, []);
  const knownActions = useMemo(() => Array.from(new Set([...Object.keys(actionLabels), ...events.map(item => item.action)])).sort(), [events]);

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle eyebrow="Audit trail" title="审计日志" description="记录有业务或安全意义的 actor · action · target · outcome；不记录密码、Session token 或知识正文。" />
      <div class="mt-5 grid gap-3 lg:grid-cols-[1.4fr_.7fr_.9fr_.55fr_auto]">
        <div class="relative"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索 actor / action / target" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <input value={actor} onInput={event => setActor((event.target as HTMLInputElement).value)} placeholder="Actor" class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/>
        <select value={action} onChange={event => setAction((event.target as HTMLSelectElement).value)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none dark:border-slate-700 dark:bg-slate-950"><option value="">全部 Action</option>{knownActions.map(value => <option value={value} key={value}>{value}</option>)}</select>
        <select value={outcome} onChange={event => setOutcome((event.target as HTMLSelectElement).value)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none dark:border-slate-700 dark:bg-slate-950"><option>ALL</option><option>SUCCESS</option><option>FAILURE</option></select>
        <button onClick={() => void load(1)} class="rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white dark:bg-white dark:text-slate-950">筛选</button>
      </div>
    </section>

    <section class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
      {loading ? <div class="p-5"><LoadingBlock rows={6}/></div> : events.length === 0 ? <div class="p-5"><EmptyState icon="shield" title="没有匹配的审计事件" description="登录、用户管理、上传、入库、问答、来源打包等重要操作会出现在这里。"/></div> : <div class="overflow-x-auto"><table class="min-w-full text-sm">
        <thead class="bg-slate-50 text-left text-[11px] font-semibold uppercase tracking-[0.1em] text-slate-400 dark:bg-slate-950/50"><tr><th class="px-4 py-3">Time</th><th class="px-4 py-3">Actor</th><th class="px-4 py-3">Action</th><th class="px-4 py-3">Target</th><th class="px-4 py-3">Outcome</th><th class="px-4 py-3">Context</th></tr></thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-800">{events.map(item => <tr key={item.event_id} class="hover:bg-slate-50/70 dark:hover:bg-slate-950/30">
          <td class="whitespace-nowrap px-4 py-3 text-xs text-slate-400">{formatDate(item.created_at)}</td>
          <td class="px-4 py-3"><div class="font-semibold text-slate-700 dark:text-slate-200">{item.actor_username}</div><div class="mt-0.5 text-[11px] text-slate-400">{item.actor_role}</div></td>
          <td class="px-4 py-3"><div class="font-semibold text-slate-700 dark:text-slate-200">{actionLabels[item.action] || item.action}</div><div class="mt-0.5 font-mono text-[11px] text-slate-400">{item.action}</div></td>
          <td class="max-w-xs px-4 py-3"><div class="truncate font-medium text-slate-600 dark:text-slate-300">{item.target_name || item.target_id || '—'}</div><div class="mt-0.5 truncate text-[11px] text-slate-400">{item.target_type || '—'} · {shortID(item.target_id, 12)}</div></td>
          <td class="px-4 py-3"><StatusBadge value={item.outcome}/></td>
          <td class="px-4 py-3"><button onClick={() => setSelected(item)} class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-semibold text-slate-500 hover:border-blue-300 hover:text-blue-600 dark:border-slate-700">查看</button></td>
        </tr>)}</tbody>
      </table></div>}
      <div class="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => void load(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => void load(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>
    </section>
    {selected && <AuditDrawer event={selected} onClose={() => setSelected(null)}/>} 
  </div>;
}

function AuditDrawer({ event, onClose }: { event: EnterpriseAuditEvent; onClose: () => void }) {
  return <div class="fixed inset-0 z-[80] flex justify-end bg-slate-950/35 backdrop-blur-[1px]" onClick={onClose}><aside class="h-full w-full max-w-xl overflow-y-auto border-l border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-800 dark:bg-slate-900" onClick={e => e.stopPropagation()}><div class="flex items-start justify-between gap-3"><div><div class="text-xs font-semibold uppercase tracking-[0.14em] text-blue-600">Audit event</div><h3 class="mt-1 text-lg font-semibold">{actionLabels[event.action] || event.action}</h3><div class="mt-1 font-mono text-xs text-slate-400">{event.event_id}</div></div><button onClick={onClose} class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><Icon name="close" size={16}/></button></div>
    <div class="mt-6 grid gap-3 sm:grid-cols-2"><AuditInfo label="Actor" value={`${event.actor_username} · ${event.actor_role}`}/><AuditInfo label="Outcome" value={event.outcome}/><AuditInfo label="Target" value={`${event.target_type || '—'} · ${event.target_name || event.target_id || '—'}`}/><AuditInfo label="Created" value={formatDate(event.created_at)}/><AuditInfo label="IP" value={event.ip_address || '—'}/><AuditInfo label="Request ID" value={event.request_id || '—'}/></div>
    <div class="mt-5"><div class="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">Details</div><pre class="overflow-x-auto rounded-xl bg-slate-50 p-4 text-xs leading-6 text-slate-600 dark:bg-slate-950 dark:text-slate-300">{JSON.stringify(event.details || {}, null, 2)}</pre></div>
    <div class="mt-5"><div class="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">User Agent</div><div class="break-words rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-500 dark:bg-slate-950">{event.user_agent || '—'}</div></div></aside></div>;
}

function AuditInfo({ label, value }: { label: string; value: string }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 break-words text-sm text-slate-700 dark:text-slate-200">{value}</div></div>; }
