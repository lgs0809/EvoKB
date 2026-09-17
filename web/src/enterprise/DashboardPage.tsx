import type { EnterpriseDocumentVersion, EnterpriseOverview, EnterpriseTask, EnterpriseWiki } from '../lib/api';
import type { EnterpriseSection } from './EnterpriseShell';
import { EmptyState, Icon, LoadingBlock, MetricCard, SectionTitle, StatusBadge, formatDate, formatNumber } from './ui';

export function DashboardPage({ overview, documents, wikis, tasks, onNavigate }: {
  overview: EnterpriseOverview | null;
  documents: EnterpriseDocumentVersion[];
  wikis: EnterpriseWiki[];
  tasks: EnterpriseTask[];
  onNavigate: (section: EnterpriseSection) => void;
}) {
  if (!overview) return <LoadingBlock rows={6} />;
  const recentDocuments = documents.slice(0, 6);
  const popular = [...documents].filter(item => item.effective_hits > 0).sort((a, b) => b.effective_hits - a.effective_hits).slice(0, 5);
  const recentTasks = tasks.slice(0, 5);
  const activeWiki = wikis.filter(item => item.lifecycle === 'ACTIVE').length;
  const attention = [
    { label: '失败的资料版本', count: overview.failed_versions, section: 'documents' as EnterpriseSection, detail: '查看失败原因', tone: 'rose' },
    { label: '待更新摘要', count: overview.stale_summaries, section: 'documents' as EnterpriseSection, detail: '重新生成摘要', tone: 'amber' },
    { label: '待更新百科', count: overview.stale_wikis, section: 'wiki' as EnterpriseSection, detail: '更新百科内容', tone: 'violet' },
    { label: '生成推荐', count: overview.recommended_sources, section: 'documents' as EnterpriseSection, detail: '查看推荐资料', tone: 'blue' },
  ].filter(item => item.count > 0);

  return <div class="space-y-6">
    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="资料" value={formatNumber(overview.documents)} hint={`${formatNumber(overview.active_versions)} 个可用版本`} icon="documents" tone="blue" />
      <MetricCard label="知识片段" value={formatNumber(overview.raw_blocks)} hint={`${formatNumber(overview.active_evidence)} 条有效证据`} icon="layers" tone="emerald" />
      <MetricCard label="企业百科" value={formatNumber(overview.active_wikis || activeWiki)} hint={`${formatNumber(overview.stale_wikis)} 个待更新`} icon="wiki" tone="violet" />
      <MetricCard label="关系" value={formatNumber(overview.active_relations)} hint={`${formatNumber(overview.effective_hits)} 次有效引用`} icon="graph" tone="amber" />
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle title="需要关注" />
      {attention.length > 0 ? <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-5">{attention.map(item => <button key={item.label} onClick={() => onNavigate(item.section)} class="group rounded-2xl border border-slate-200 p-4 text-left transition hover:-translate-y-0.5 hover:border-blue-300 hover:shadow-sm dark:border-slate-800"><div class="flex items-start justify-between gap-3"><div class={`flex h-9 w-9 items-center justify-center rounded-xl ${attentionTone(item.tone)}`}><Icon name={item.tone === 'rose' ? 'warning' : item.tone === 'violet' ? 'wiki' : item.tone === 'blue' ? 'sparkles' : 'clock'} size={17}/></div><span class="text-2xl font-semibold">{formatNumber(item.count)}</span></div><div class="mt-3 text-sm font-semibold text-slate-700 dark:text-slate-200">{item.label}</div><div class="mt-1 text-xs text-slate-400">{item.detail}</div></button>)}</div> : <div class="mt-5 flex items-center gap-3 rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/30 dark:text-emerald-300"><Icon name="check" size={18}/><span class="font-semibold">暂无待处理事项</span></div>}
    </section>

    <section class="grid gap-6 xl:grid-cols-[1.2fr_.8fr]">
      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle title="最近资料" action={<button onClick={() => onNavigate('documents')} class="text-sm font-medium text-blue-600 hover:text-blue-700 dark:text-blue-400">查看全部</button>} />
        <div class="mt-5 divide-y divide-slate-100 dark:divide-slate-800">
          {recentDocuments.map(doc => <button key={doc.version_id} onClick={() => onNavigate('documents')} class="flex w-full items-center gap-4 py-4 text-left first:pt-1 last:pb-0">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300"><Icon name="file" size={18}/></div>
            <div class="min-w-0 flex-1"><div class="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">{doc.logical_name}</div><div class="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-400"><span>版本 {doc.version_no}</span><span>·</span><span>{doc.document_type || '未分类'}</span><span>·</span><span>{formatDate(doc.parsed_at)}</span></div></div>
            <StatusBadge value={doc.lifecycle} />
          </button>)}
          {recentDocuments.length === 0 && <div class="py-8 text-center text-sm text-slate-400">暂无资料。</div>}
        </div>
      </div>

      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle title="知识质量" />
        <div class="mt-6 space-y-5">
          <HealthBar label="摘要事实校验" value={overview.grounded_summaries} total={overview.active_summaries} />
          <HealthBar label="摘要完整性" value={overview.covered_summaries} total={overview.active_summaries} />
          <HealthBar label="可用版本" value={overview.active_versions} total={Math.max(overview.versions, overview.active_versions)} />
        </div>
        <div class="mt-6 grid grid-cols-3 gap-2 border-t border-slate-100 pt-5 text-center dark:border-slate-800">
          <MiniStat label="待更新摘要" value={overview.stale_summaries} />
          <MiniStat label="待更新百科" value={overview.stale_wikis} />
          <MiniStat label="生成推荐" value={overview.recommended_sources} />
        </div>
      </div>
    </section>

    <section class="grid gap-6 xl:grid-cols-2">
      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle title="热门资料" action={<button onClick={() => onNavigate('documents')} class="text-sm font-medium text-blue-600 dark:text-blue-400">查看资料</button>} />
        <div class="mt-5 space-y-2">
          {popular.map((doc, i) => <div key={doc.version_id} class="flex items-center gap-3 rounded-xl border border-slate-100 px-3 py-3 dark:border-slate-800">
            <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-100 text-xs font-semibold text-slate-500 dark:bg-slate-800">{i + 1}</div>
            <div class="min-w-0 flex-1"><div class="truncate text-sm font-medium">{doc.logical_name}</div><div class="text-xs text-slate-400">v{doc.version_no} · {doc.document_type || doc.source_department || '企业资料'}</div></div>
            <div class="text-right"><div class="text-sm font-semibold">{doc.effective_hits}</div><div class="text-[10px] text-slate-400">引用</div></div>
          </div>)}
          {popular.length === 0 && <EmptyState icon="activity" title="暂无热门资料" description="有实际引用后会在这里显示。" />}
        </div>
      </div>

      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle title="最近知识生成" action={<button onClick={() => onNavigate('tasks')} class="text-sm font-medium text-blue-600 dark:text-blue-400">查看全部</button>} />
        <div class="mt-5 space-y-2">
          {recentTasks.map(task => <div key={task.task_id} class="flex items-center gap-3 rounded-xl border border-slate-100 px-3 py-3 dark:border-slate-800">
            <div class="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name={task.status === 'FAILED' ? 'warning' : task.status === 'RUNNING' ? 'activity' : 'tasks'} size={17}/></div>
            <div class="min-w-0 flex-1"><div class="truncate text-sm font-medium">{task.logical_name || task.task_type}</div><div class="text-xs text-slate-400">{formatDate(task.created_at)}</div></div>
            <StatusBadge value={task.status}/>
          </div>)}
          {recentTasks.length === 0 && <EmptyState icon="sparkles" title="暂无知识生成记录" description="生成摘要、关系或企业百科后会显示在这里。" />}
        </div>
      </div>
    </section>
  </div>;
}

function HealthBar({ label, value, total }: { label: string; value: number; total: number }) {
  const percentage = total > 0 ? Math.min(100, Math.round((value / total) * 100)) : 0;
  return <div><div class="mb-2 flex items-center justify-between text-sm"><span class="font-medium text-slate-700 dark:text-slate-200">{label}</span><span class="text-slate-400">{total ? `${percentage}%` : '暂无数据'}</span></div><div class="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800"><div class="h-full rounded-full bg-slate-900 transition-all dark:bg-white" style={{ width: total ? `${percentage}%` : '0%' }}/></div></div>;
}

function MiniStat({ label, value, danger }: { label: string; value: number; danger?: boolean }) {
  return <div><div class={`text-lg font-semibold ${danger ? 'text-rose-600 dark:text-rose-400' : 'text-slate-800 dark:text-slate-100'}`}>{formatNumber(value)}</div><div class="mt-1 text-[10px] text-slate-400">{label}</div></div>;
}

function attentionTone(tone: string) {
  switch (tone) {
    case 'rose': return 'bg-rose-50 text-rose-600 dark:bg-rose-950/40 dark:text-rose-300';
    case 'amber': return 'bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-300';
    case 'violet': return 'bg-violet-50 text-violet-600 dark:bg-violet-950/40 dark:text-violet-300';
    case 'blue': return 'bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300';
    default: return 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300';
  }
}
