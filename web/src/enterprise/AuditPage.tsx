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
  'access.settings.update': '修改访问设置',
  'ingestion.batch.create': '创建入库批次',
  'ingestion.file.metadata.update': '调整资料信息',
  'ingestion.batch.start': '开始资料入库',
  'ingestion.file.retry': '重新处理资料',
  'document.upload.create': '单文件上传',
  'knowledge_generation.recommend': '建议生成高级知识',
  'distill.recommend': '建议生成高级知识',
  'evidence.source.view': '查看原始资料',
  'evidence.package.create': '打包来源资料',
  'evaluation.artifact.run': '执行知识质量评测',
  'compilation.summary.run': '生成摘要',
  'compilation.graph.run': '生成关系',
  'compilation.wiki.run': '生成企业百科',
  'compilation.wiki_keyword.run': '生成百科关系',
  // Compatibility with historical audit values already persisted in existing databases.
  'access.user_upload.update': '修改普通用户上传权限',
  'document.batch.create': '创建入库批次',
  'document.batch.file.update': '调整资料信息',
  'document.batch.start': '开始资料入库',
  'document.ingest.retry': '重新处理资料',
  'document.distill.mark': '建议生成高级知识',
  'knowledge.ask': '知识查询',
  'source_package.create': '打包来源资料',
  'evidence.source.open': '查看原始资料',
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
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle title="审计日志" description="查看关键操作记录。" />
      <div class="mt-5 grid gap-3 lg:grid-cols-[1.4fr_.8fr_.9fr_.7fr_auto]">
        <div class="relative"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索用户、动作或对象" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <input value={actor} onInput={event => setActor((event.target as HTMLInputElement).value)} placeholder="用户" class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/>
        <select value={action} onChange={event => setAction((event.target as HTMLSelectElement).value)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none dark:border-slate-700 dark:bg-slate-950"><option value="">全部操作</option>{knownActions.map(value => <option value={value} key={value}>{actionLabels[value] || value}</option>)}</select>
        <select value={outcome} onChange={event => setOutcome((event.target as HTMLSelectElement).value)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none dark:border-slate-700 dark:bg-slate-950"><option value="ALL">全部结果</option><option value="SUCCESS">成功</option><option value="FAILURE">失败</option></select>
        <button onClick={() => void load(1)} class="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400">筛选</button>
      </div>
    </section>

    <section class="grid gap-5 xl:grid-cols-[minmax(0,1.3fr)_minmax(360px,.7fr)]">
      <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {loading ? <div class="p-5"><LoadingBlock rows={7}/></div> : events.length === 0 ? <div class="p-5"><EmptyState icon="shield" title="暂无审计记录" description="关键操作发生后会显示在这里。"/></div> : <div class="divide-y divide-slate-100 dark:divide-slate-800">{events.map(item => <button key={item.event_id} onClick={() => setSelected(item)} class={`flex w-full items-start gap-4 px-4 py-4 text-left transition sm:px-5 ${selected?.event_id === item.event_id ? 'bg-blue-50/70 dark:bg-blue-950/20' : 'hover:bg-slate-50 dark:hover:bg-slate-950/30'}`}>
          <div class="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name="shield" size={17}/></div>
          <div class="min-w-0 flex-1"><div class="flex flex-wrap items-center gap-2"><div class="truncate text-sm font-semibold">{actionLabels[item.action] || item.action}</div><StatusBadge value={item.outcome}/></div><div class="mt-1 truncate text-xs text-slate-400">{item.actor_username || item.actor_user_id || '系统'} · {item.target_type || '—'} · {shortID(item.target_id, 12)}</div></div>
          <div class="hidden text-right text-xs text-slate-400 sm:block">{formatDate(item.created_at)}</div>
        </button>)}</div>}
        <div class="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => void load(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => void load(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>
      </div>

      <aside class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {!selected ? <EmptyState icon="shield" title="选择一条审计记录" description="查看操作对象、结果和附加信息。"/> : <div class="space-y-4"><div><StatusBadge value={selected.outcome}/><h3 class="mt-3 text-lg font-semibold">{actionLabels[selected.action] || selected.action}</h3><div class="mt-1 text-xs text-slate-400">{formatDate(selected.created_at)}</div></div><div class="grid grid-cols-2 gap-3 text-xs"><Info label="用户" value={selected.actor_username || selected.actor_user_id || '系统'}/><Info label="对象类型" value={selected.target_type || '—'}/><Info label="对象编号" value={selected.target_id || '—'}/><Info label="操作代码" value={selected.action}/></div>{selected.details && <div><div class="mb-2 text-xs font-semibold text-slate-400">详细信息</div><pre class="overflow-x-auto rounded-xl bg-slate-50 p-3 text-xs leading-6 text-slate-600 dark:bg-slate-950/60 dark:text-slate-300">{typeof selected.details === 'string' ? selected.details : JSON.stringify(selected.details, null, 2)}</pre></div>}</div>}
      </aside>
    </section>
  </div>;
}

function Info({ label, value }: { label: string; value: string }) {
  return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] font-semibold tracking-wide text-slate-400">{label}</div><div class="mt-1 break-all text-xs font-medium text-slate-600 dark:text-slate-300">{value}</div></div>;
}
