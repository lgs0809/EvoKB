import { useEffect, useState } from 'preact/hooks';
import type { EnterpriseCapabilities, EnterpriseOverview } from '../lib/api';
import { Icon, MetricCard, SectionTitle, formatNumber, pct } from './ui';

export function AdminPage({ capabilities, overview }: { capabilities: EnterpriseCapabilities | null; overview: EnterpriseOverview | null }) {
  const [serverOnline, setServerOnline] = useState<boolean | null>(null);
  useEffect(() => {
    fetch('/health').then(response => setServerOnline(response.ok)).catch(() => setServerOnline(false));
  }, []);

  const capabilityRows: Array<{ key: keyof EnterpriseCapabilities; label: string; description: string; icon: 'documents' | 'tasks' | 'evidence' | 'search' | 'download' | 'sparkles' | 'graph' | 'server' }> = [
    { key: 'documents', label: 'Document / Version', description: 'MySQL knowledge asset metadata', icon: 'documents' },
    { key: 'tasks', label: 'Compile Task', description: 'Pipeline execution visibility', icon: 'tasks' },
    { key: 'evidence', label: 'Evidence Resolver', description: 'Exact Raw provenance resolution', icon: 'evidence' },
    { key: 'search', label: 'Unified Retrieval', description: 'Raw / Summary / Wiki / Graph', icon: 'search' },
    { key: 'package_sources', label: 'Source Package', description: 'Version-deduplicated ZIP delivery', icon: 'download' },
    { key: 'promotion', label: 'Distillation Promotion', description: 'Final effective-hit recommendation', icon: 'sparkles' },
    { key: 'graph', label: 'Neo4j Graph', description: 'Bounded evidence-backed traversal', icon: 'graph' },
    { key: 'web', label: 'Enterprise Web', description: 'Same-origin Preact workbench', icon: 'server' },
  ];

  return <div class="space-y-6">
    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <MetricCard label="Knowledge Server" value={serverOnline == null ? 'CHECK' : serverOnline ? 'ONLINE' : 'OFFLINE'} hint="GET /health" icon="server" tone={serverOnline === false ? 'amber' : 'emerald'}/>
      <MetricCard label="Active Version" value={formatNumber(overview?.active_versions)} hint={`${formatNumber(overview?.failed_versions)} failed versions`} icon="documents" tone="blue"/>
      <MetricCard label="Grounding Pass" value={overview ? pct(overview.grounded_summaries, overview.active_summaries) : '—'} hint="ACTIVE Summary" icon="shield" tone="emerald"/>
      <MetricCard label="Coverage Pass" value={overview ? pct(overview.covered_summaries, overview.active_summaries) : '—'} hint="ACTIVE Summary" icon="activity" tone="violet"/>
    </section>

    <section class="grid gap-6 xl:grid-cols-[1.05fr_.95fr]">
      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Runtime capabilities" title="已接入能力" description="这里展示 Server 当前实际装配的 adapter，不展示 API Key、密码、DSN 或任何敏感环境变量。" />
        <div class="mt-5 grid gap-3 sm:grid-cols-2">{capabilityRows.map(row => {
          const enabled = Boolean(capabilities?.[row.key]);
          return <div key={row.key} class="flex items-center gap-3 rounded-xl border border-slate-100 p-3 dark:border-slate-800"><div class={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${enabled ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-300' : 'bg-slate-100 text-slate-400 dark:bg-slate-800'}`}><Icon name={row.icon} size={17}/></div><div class="min-w-0 flex-1"><div class="flex items-center justify-between gap-2"><div class="truncate text-sm font-semibold">{row.label}</div><span class={`text-[10px] font-semibold uppercase tracking-wide ${enabled ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400'}`}>{enabled ? 'wired' : 'off'}</span></div><div class="mt-0.5 truncate text-[11px] text-slate-400">{row.description}</div></div></div>;
        })}</div>
      </div>

      <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle eyebrow="Trust contract" title="企业知识质量门" description="这些是系统行为约束，不是装饰性的“AI 可信度分数”。" />
        <div class="mt-5 space-y-3">
          <TrustRule title="Raw Retrieval 是第一硬 Gate" detail="BM25 + Vector + Metadata Filter 先保证原始资料可找回；不会为了高阶知识牺牲 Raw。" />
          <TrustRule title="Grounding 防编错，Coverage 防漏" detail="Summary / Wiki / Graph 都要求 Precision 与 Recall 两类质量门，而不是只检查生成内容看起来合理。" />
          <TrustRule title="Final Candidate 必须 Evidence-resolvable" detail="任何高阶候选如果不能回到 ACTIVE Raw Evidence，会在最终交付前被过滤。" />
          <TrustRule title="版本失效不改业务事实有效期" detail="V1 文件被 V2 替换只撤销旧 Evidence Support，不会错误地修改 Relation Fact 的 valid_to。" />
        </div>
      </div>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle eyebrow="Storage responsibilities" title="企业数据平面" description="四类存储各自负责清晰职责，上层通过 adapter 组合，不强塞回一个单体 Store。" />
      <div class="mt-5 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StorageCard title="MySQL" subtitle="事实与生命周期" items={['Document / Version', 'Evidence metadata', 'Summary / Wiki facts', 'Promotion & Task']}/>
        <StorageCard title="MinIO" subtitle="原始与派生对象" items={['Original files', 'Parsed artifacts', 'Summary / Wiki files', 'Source packages']}/>
        <StorageCard title="OpenSearch" subtitle="检索候选" items={['Raw BM25', 'Raw Vector', 'Summary / Wiki index', 'Metadata filter']}/>
        <StorageCard title="Neo4j" subtitle="关系与多跳" items={['Canonical Entity', 'Relation Fact', 'Evidence Support', 'Bounded traversal']}/>
      </div>
    </section>

    <section class="rounded-2xl border border-blue-200 bg-blue-50 p-5 text-sm leading-7 text-blue-900 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-200"><div class="flex items-start gap-3"><Icon name="shield" size={20} class="mt-1 shrink-0"/><div><div class="font-semibold">安全边界</div><p class="mt-1 text-blue-800/80 dark:text-blue-300/80">Web API 支持 Bearer Token；除 /health 外都受同一安全中间件保护。JSON 请求拒绝未知字段并限制 1 MiB body。系统状态页面故意不回显模型 Key、数据库密码、MinIO Secret 或 Neo4j 凭据。</p></div></div></section>
  </div>;
}

function TrustRule({ title, detail }: { title: string; detail: string }) {
  return <div class="flex gap-3 rounded-xl bg-slate-50 p-3 dark:bg-slate-950/50"><div class="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600 dark:bg-emerald-950/50 dark:text-emerald-300"><Icon name="check" size={14}/></div><div><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">{title}</div><div class="mt-1 text-xs leading-5 text-slate-400">{detail}</div></div></div>;
}

function StorageCard({ title, subtitle, items }: { title: string; subtitle: string; items: string[] }) {
  return <div class="rounded-2xl border border-slate-200 p-4 dark:border-slate-800"><div class="flex items-center gap-3"><div class="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name="database" size={17}/></div><div><div class="text-sm font-semibold">{title}</div><div class="text-[11px] text-slate-400">{subtitle}</div></div></div><ul class="mt-4 space-y-2">{items.map(item => <li key={item} class="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400"><span class="h-1.5 w-1.5 rounded-full bg-slate-300 dark:bg-slate-600"/>{item}</li>)}</ul></div>;
}
