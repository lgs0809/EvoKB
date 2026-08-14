import { useMemo, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, packageEnterpriseSources, type EnterpriseEvidence, type EnterpriseEvidenceListItem, type EnterpriseOverview, type EnterprisePublishedPackage } from '../lib/api';
import { EmptyState, Icon, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge, formatDate, formatNumber, shortID } from './ui';

export function EvidencePage({ evidences, overview, onOpenEvidence, onError }: {
  evidences: EnterpriseEvidenceListItem[];
  overview: EnterpriseOverview | null;
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [activeOnly, setActiveOnly] = useState(true);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [packaging, setPackaging] = useState(false);
  const [published, setPublished] = useState<EnterprisePublishedPackage | null>(null);

  const filtered = useMemo(() => evidences.filter(item => {
    if (activeOnly && !item.active) return false;
    if (!query.trim()) return true;
    const needle = query.trim().toLowerCase();
    return [item.logical_name, item.original_filename, item.evidence_id, item.version_id, item.block_id, item.excerpt].some(value => value?.toLowerCase().includes(needle));
  }), [evidences, query, activeOnly]);

  const open = async (id: string) => {
    try {
      onOpenEvidence(await fetchEnterpriseEvidence(id));
      onError('');
    } catch (err) {
      onError(String(err));
    }
  };

  const toggle = (item: EnterpriseEvidenceListItem) => {
    if (!item.active) return;
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(item.evidence_id)) next.delete(item.evidence_id);
      else next.add(item.evidence_id);
      return next;
    });
    setPublished(null);
  };

  const packageSelected = async () => {
    if (!selected.size) return;
    setPackaging(true);
    try {
      setPublished(await packageEnterpriseSources(Array.from(selected)));
      onError('');
    } catch (err) {
      onError(String(err));
    } finally {
      setPackaging(false);
    }
  };

  return <div class="space-y-6">
    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <EvidenceMetric label="ACTIVE Evidence" value={formatNumber(overview?.active_evidence)} icon="shield" tone="emerald"/>
      <EvidenceMetric label="Raw Blocks" value={formatNumber(overview?.raw_blocks)} icon="layers" tone="blue"/>
      <EvidenceMetric label="Graph Supports" value={formatNumber(overview?.active_graph_supports)} icon="graph" tone="amber"/>
      <EvidenceMetric label="Effective Hits" value={formatNumber(overview?.effective_hits)} icon="activity" tone="violet"/>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle eyebrow="Raw provenance" title="证据中心" description="Evidence 是企业版知识链的可信根。高阶 Summary / Wiki / Graph 最终都必须能解析回这里。" action={selected.size > 0 ? <PrimaryButton onClick={() => void packageSelected()} disabled={packaging} icon="download">{packaging ? '打包中…' : `打包 ${selected.size} 条 Evidence`}</PrimaryButton> : undefined} />
      <div class="mt-5 flex flex-col gap-3 md:flex-row">
        <div class="relative flex-1"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} placeholder="搜索文档、Evidence ID、Version ID 或原文片段" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-emerald-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <button onClick={() => setActiveOnly(value => !value)} class={`inline-flex items-center justify-center gap-2 rounded-xl border px-3.5 py-2.5 text-sm font-medium ${activeOnly ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300' : 'border-slate-200 bg-white text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300'}`}><Icon name="filter" size={15}/>{activeOnly ? '仅 ACTIVE' : '全部 Evidence'}</button>
      </div>
      {published && <div class="mt-4 flex flex-col gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800 sm:flex-row sm:items-center sm:justify-between dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300"><div><div class="font-semibold">资料包已按 Document Version 去重生成</div><div class="mt-1 text-xs opacity-80">{published.manifest?.versions?.length || 0} 个原始版本 · 有效至 {formatDate(published.expires_at)}</div></div><a href={published.download_url} target="_blank" rel="noreferrer" class="inline-flex items-center gap-2 rounded-lg bg-emerald-700 px-3 py-2 text-xs font-semibold text-white"><Icon name="download" size={14}/>下载 ZIP</a></div>}
    </section>

    <section class="grid gap-3">
      {filtered.map(item => <article key={item.evidence_id} class={`rounded-2xl border bg-white p-4 shadow-sm transition dark:bg-slate-900 ${selected.has(item.evidence_id) ? 'border-emerald-300 ring-2 ring-emerald-100 dark:border-emerald-800 dark:ring-emerald-950' : 'border-slate-200/80 dark:border-slate-800'}`}>
        <div class="flex flex-col gap-4 lg:flex-row lg:items-center">
          <div class="flex items-start gap-3 lg:w-[32%]"><button disabled={!item.active} onClick={() => toggle(item)} class={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded border ${selected.has(item.evidence_id) ? 'border-emerald-600 bg-emerald-600 text-white' : 'border-slate-300 text-transparent dark:border-slate-600'} disabled:cursor-not-allowed disabled:opacity-30`} aria-label="选择 Evidence"><Icon name="check" size={12}/></button><div class="min-w-0"><div class="flex items-center gap-2"><div class="truncate text-sm font-semibold">{item.logical_name}</div><StatusBadge value={item.active ? 'ACTIVE' : 'INACTIVE'}/></div><div class="mt-1 truncate text-xs text-slate-400">{item.original_filename} · v{item.version_no}</div></div></div>
          <div class="min-w-0 flex-1"><p class="line-clamp-2 text-sm leading-6 text-slate-600 dark:text-slate-300">{item.excerpt || '—'}</p><div class="mt-2 flex flex-wrap gap-2 text-[11px] text-slate-400"><span>Evidence {shortID(item.evidence_id)}</span><span>Block {shortID(item.block_id)}</span><span>{item.block_type}</span><span>{locationLabel(item.raw_location)}</span></div></div>
          <div class="flex shrink-0 items-center gap-2"><span class="hidden text-xs text-slate-400 xl:block">{formatDate(item.created_at)}</span><SecondaryButton onClick={() => void open(item.evidence_id)} icon="evidence">查看原文</SecondaryButton></div>
        </div>
      </article>)}
      {filtered.length === 0 && <EmptyState icon="evidence" title="没有匹配的 Evidence" description="Evidence 会在 Raw ingestion 成功后创建，并与精确 Document Version / Block / Location 绑定。" />}
    </section>
  </div>;
}

function EvidenceMetric({ label, value, icon, tone }: { label: string; value: string; icon: 'shield' | 'layers' | 'graph' | 'activity'; tone: 'emerald' | 'blue' | 'amber' | 'violet' }) {
  const toneClass = { emerald: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-950/40 dark:text-emerald-300', blue: 'text-blue-600 bg-blue-50 dark:bg-blue-950/40 dark:text-blue-300', amber: 'text-amber-600 bg-amber-50 dark:bg-amber-950/40 dark:text-amber-300', violet: 'text-violet-600 bg-violet-50 dark:bg-violet-950/40 dark:text-violet-300' }[tone];
  return <div class="flex items-center gap-4 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900"><div class={`flex h-11 w-11 items-center justify-center rounded-xl ${toneClass}`}><Icon name={icon} size={19}/></div><div><div class="text-xl font-semibold">{value}</div><div class="text-xs text-slate-400">{label}</div></div></div>;
}

function locationLabel(location: Record<string, unknown>) {
  if (!location) return 'Location —';
  const parts: string[] = [];
  if (location.page_start != null) parts.push(`Page ${String(location.page_start)}`);
  else if (location.page != null) parts.push(`Page ${String(location.page)}`);
  if (location.block_no != null) parts.push(`Block ${String(location.block_no)}`);
  if (location.slide_no != null) parts.push(`Slide ${String(location.slide_no)}`);
  if (location.sheet_name) parts.push(`Sheet ${String(location.sheet_name)}`);
  return parts.length ? parts.join(' · ') : 'Raw location';
}
