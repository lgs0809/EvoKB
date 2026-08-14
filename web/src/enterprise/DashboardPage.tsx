import type { EnterpriseDocumentVersion, EnterpriseOverview, EnterpriseTask, EnterpriseWiki } from '../lib/api';
import type { EnterpriseSection } from './EnterpriseShell';
import { EmptyState, Icon, LoadingBlock, MetricCard, SectionTitle, StatusBadge, formatDate, formatNumber, pct } from './ui';

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

  return <div class="space-y-8">
    <section class="relative overflow-hidden rounded-[28px] border border-slate-200/80 bg-slate-950 px-6 py-7 text-white shadow-xl shadow-slate-900/10 sm:px-8 sm:py-9 dark:border-slate-800">
      <div class="absolute -right-20 -top-28 h-72 w-72 rounded-full bg-blue-500/20 blur-3xl" />
      <div class="absolute -bottom-32 right-40 h-64 w-64 rounded-full bg-violet-500/15 blur-3xl" />
      <div class="relative grid gap-8 lg:grid-cols-[1.35fr_.65fr] lg:items-end">
        <div>
          <div class="mb-4 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-medium text-white/70"><Icon name="shield" size={14} />Evidence-first enterprise knowledge</div>
          <h2 class="max-w-3xl text-3xl font-semibold tracking-tight sm:text-4xl">任何知识都能回到原始证据，<br class="hidden sm:block"/>任何变化都有清晰版本。</h2>
          <p class="mt-4 max-w-2xl text-sm leading-7 text-slate-300">当前知识库把 Raw、Summary、Wiki 与 Graph 统一到同一条 Evidence 链上。高阶知识无法解析回 Raw 时，不会进入最终可信结果。</p>
          <div class="mt-6 flex flex-wrap gap-3">
            <button onClick={() => onNavigate('search')} class="inline-flex items-center gap-2 rounded-xl bg-white px-4 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-slate-100"><Icon name="search" size={16}/>开始可信检索</button>
            <button onClick={() => onNavigate('documents')} class="inline-flex items-center gap-2 rounded-xl border border-white/15 bg-white/5 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-white/10"><Icon name="documents" size={16}/>查看知识资产</button>
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <HeroStat label="Grounding Pass" value={pct(overview.grounded_summaries, overview.active_summaries)} />
          <HeroStat label="Coverage Pass" value={pct(overview.covered_summaries, overview.active_summaries)} />
          <HeroStat label="Active Evidence" value={formatNumber(overview.active_evidence)} />
          <HeroStat label="Graph Support" value={formatNumber(overview.active_graph_supports)} />
        </div>
      </div>
    </section>

    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="知识文档" value={formatNumber(overview.documents)} hint={`${formatNumber(overview.active_versions)} 个 ACTIVE Version`} icon="documents" tone="blue" />
      <MetricCard label="Raw Knowledge Blocks" value={formatNumber(overview.raw_blocks)} hint={`${formatNumber(overview.active_evidence)} 条 ACTIVE Evidence`} icon="layers" tone="emerald" />
      <MetricCard label="企业 Wiki" value={formatNumber(overview.active_wikis || activeWiki)} hint={`${formatNumber(overview.stale_wikis)} 个待更新版本`} icon="wiki" tone="violet" />
      <MetricCard label="Evidence-backed Relations" value={formatNumber(overview.active_relations)} hint={`${formatNumber(overview.effective_hits)} 次最终有效引用`} icon="graph" tone="amber" />
    </section>

    <section class="grid gap-6 xl:grid-cols-[1.2fr_.8fr]">
      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Knowledge evolution" title="最近知识变化" description="最近进入或更新的 Document Version，以及它们的编译状态。" action={<button onClick={() => onNavigate('documents')} class="text-sm font-medium text-blue-600 hover:text-blue-700 dark:text-blue-400">查看全部</button>} />
        <div class="mt-5 divide-y divide-slate-100 dark:divide-slate-800">
          {recentDocuments.map(doc => <button key={doc.version_id} onClick={() => onNavigate('documents')} class="flex w-full items-center gap-4 py-4 text-left first:pt-1 last:pb-0">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300"><Icon name="file" size={18}/></div>
            <div class="min-w-0 flex-1"><div class="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">{doc.logical_name}</div><div class="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-400"><span>Version {doc.version_no}</span><span>·</span><span>{doc.document_type || '未分类'}</span><span>·</span><span>{formatDate(doc.parsed_at)}</span></div></div>
            <StatusBadge value={doc.lifecycle} />
          </button>)}
          {recentDocuments.length === 0 && <div class="py-8 text-center text-sm text-slate-400">暂无文档版本。</div>}
        </div>
      </div>

      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Knowledge health" title="知识健康度" description="只展示可从真实状态计算的质量门，不生成虚假综合评分。" />
        <div class="mt-6 space-y-5">
          <HealthBar label="Summary Grounding" value={overview.grounded_summaries} total={overview.active_summaries} />
          <HealthBar label="Summary Coverage" value={overview.covered_summaries} total={overview.active_summaries} />
          <HealthBar label="Active Version" value={overview.active_versions} total={Math.max(overview.versions, overview.active_versions)} />
        </div>
        <div class="mt-6 grid grid-cols-3 gap-2 border-t border-slate-100 pt-5 text-center dark:border-slate-800">
          <MiniStat label="Stale Summary" value={overview.stale_summaries} />
          <MiniStat label="Stale Wiki" value={overview.stale_wikis} />
          <MiniStat label="Failed Task" value={overview.failed_tasks} danger={overview.failed_tasks > 0} />
        </div>
      </div>
    </section>

    <section class="grid gap-6 xl:grid-cols-2">
      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Effective usage" title="热门知识资产" description="只有最终真正交付给 Agent 的 Raw Version 才累计 effective hit。" action={<button onClick={() => onNavigate('documents')} class="text-sm font-medium text-blue-600 dark:text-blue-400">查看 Distillation</button>} />
        <div class="mt-5 space-y-2">
          {popular.map((doc, i) => <div key={doc.version_id} class="flex items-center gap-3 rounded-xl border border-slate-100 px-3 py-3 dark:border-slate-800">
            <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-100 text-xs font-semibold text-slate-500 dark:bg-slate-800">{i + 1}</div>
            <div class="min-w-0 flex-1"><div class="truncate text-sm font-medium">{doc.logical_name}</div><div class="text-xs text-slate-400">v{doc.version_no} · {doc.company || doc.document_type || 'Enterprise source'}</div></div>
            <div class="text-right"><div class="text-sm font-semibold">{doc.effective_hits}</div><div class="text-[10px] uppercase tracking-wide text-slate-400">hits</div></div>
          </div>)}
          {popular.length === 0 && <EmptyState icon="activity" title="还没有有效引用热度" description="Candidate hit 不会升温。只有最终 Evidence-resolved 结果被实际交付后才会记录。" />}
        </div>
      </div>

      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Pipeline" title="最近任务" description="解析、编译和知识更新任务的实时状态。" action={<button onClick={() => onNavigate('tasks')} class="text-sm font-medium text-blue-600 dark:text-blue-400">任务中心</button>} />
        <div class="mt-5 space-y-2">
          {recentTasks.map(task => <div key={task.task_id} class="flex items-center gap-3 rounded-xl border border-slate-100 px-3 py-3 dark:border-slate-800">
            <div class="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name={task.status === 'FAILED' ? 'warning' : task.status === 'RUNNING' ? 'activity' : 'tasks'} size={17}/></div>
            <div class="min-w-0 flex-1"><div class="truncate text-sm font-medium">{task.logical_name || task.task_type}</div><div class="text-xs text-slate-400">{task.task_type} · {formatDate(task.created_at)}</div></div>
            <StatusBadge value={task.status}/>
          </div>)}
          {recentTasks.length === 0 && <EmptyState icon="tasks" title="暂无编译任务" description="新文档进入或高价值 Source 触发编译后，任务会在这里出现。" />}
        </div>
      </div>
    </section>
  </div>;
}

function HeroStat({ label, value }: { label: string; value: string }) {
  return <div class="rounded-2xl border border-white/10 bg-white/[0.06] p-4 backdrop-blur"><div class="text-[11px] uppercase tracking-[0.12em] text-white/45">{label}</div><div class="mt-1 text-xl font-semibold">{value}</div></div>;
}

function HealthBar({ label, value, total }: { label: string; value: number; total: number }) {
  const percentage = total > 0 ? Math.min(100, Math.round((value / total) * 100)) : 0;
  return <div><div class="mb-2 flex items-center justify-between text-sm"><span class="font-medium text-slate-700 dark:text-slate-200">{label}</span><span class="text-slate-400">{total ? `${percentage}%` : '暂无样本'}</span></div><div class="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800"><div class="h-full rounded-full bg-slate-900 transition-all dark:bg-white" style={{ width: total ? `${percentage}%` : '0%' }}/></div></div>;
}

function MiniStat({ label, value, danger }: { label: string; value: number; danger?: boolean }) {
  return <div><div class={`text-lg font-semibold ${danger ? 'text-rose-600 dark:text-rose-400' : 'text-slate-800 dark:text-slate-100'}`}>{formatNumber(value)}</div><div class="mt-1 text-[10px] uppercase tracking-wide text-slate-400">{label}</div></div>;
}
