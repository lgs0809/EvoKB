import { useEffect, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, fetchEnterpriseEvidences, packageEnterpriseSources, type EnterpriseEvidence, type EnterpriseEvidenceListItem, type EnterpriseOverview, type EnterprisePublishedPackage } from '../lib/api';
import { EmptyState, Icon, LoadingBlock, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge, formatDate, formatNumber, shortID } from './ui';

export function EvidencePage({ overview, onOpenEvidence, onError }: {
  overview: EnterpriseOverview | null;
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [evidences, setEvidences] = useState<EnterpriseEvidenceListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState('');
  const [activeMode, setActiveMode] = useState<'ACTIVE' | 'ALL' | 'INACTIVE'>('ACTIVE');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [packaging, setPackaging] = useState(false);
  const [published, setPublished] = useState<EnterprisePublishedPackage | null>(null);
  const [loading, setLoading] = useState(true);
  const pageSize = 30;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  const load = async (nextPage = page, nextMode = activeMode) => {
    setLoading(true);
    try {
      const active = nextMode === 'ALL' ? undefined : nextMode === 'ACTIVE';
      const data = await fetchEnterpriseEvidences({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, active });
      setEvidences(data.evidences || []);
      setTotal(data.total || 0);
      setPage(nextPage);
      setSelected(new Set());
      setPublished(null);
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setLoading(false); }
  };

  useEffect(() => { void load(1, 'ACTIVE'); }, []);

  const open = async (id: string) => {
    try { onOpenEvidence(await fetchEnterpriseEvidence(id)); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
  };

  const toggle = (item: EnterpriseEvidenceListItem) => {
    if (!item.active) return;
    setSelected(prev => { const next = new Set(prev); if (next.has(item.evidence_id)) next.delete(item.evidence_id); else next.add(item.evidence_id); return next; });
    setPublished(null);
  };

  const packageSelected = async () => {
    if (!selected.size) return;
    setPackaging(true);
    try { setPublished(await packageEnterpriseSources(Array.from(selected))); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setPackaging(false); }
  };

  return <div class="space-y-6">
    <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <EvidenceMetric label="ACTIVE Evidence" value={formatNumber(overview?.active_evidence)} icon="shield" tone="emerald"/>
      <EvidenceMetric label="Raw Blocks" value={formatNumber(overview?.raw_blocks)} icon="layers" tone="blue"/>
      <EvidenceMetric label="Graph Supports" value={formatNumber(overview?.active_graph_supports)} icon="graph" tone="amber"/>
      <EvidenceMetric label="Effective Hits" value={formatNumber(overview?.effective_hits)} icon="activity" tone="violet"/>
    </section>

    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle eyebrow="Raw provenance" title="证据中心" description="服务端分页查询 Raw provenance。点击“查看原文”后还可以继续打开精确源文件与 PDF 页面定位。" action={selected.size > 0 ? <PrimaryButton onClick={() => void packageSelected()} disabled={packaging} icon="download">{packaging ? '打包中…' : `打包 ${selected.size} 条 Evidence`}</PrimaryButton> : undefined}/>
      <div class="mt-5 flex flex-col gap-3 md:flex-row">
        <div class="relative flex-1"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索文档、Evidence ID、Version ID 或原文片段" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-emerald-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <div class="flex gap-2">{(['ACTIVE','ALL','INACTIVE'] as const).map(mode => <button key={mode} onClick={() => { setActiveMode(mode); void load(1, mode); }} class={`rounded-xl border px-3.5 py-2.5 text-sm font-medium ${activeMode === mode ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300' : 'border-slate-200 bg-white text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300'}`}>{mode}</button>)}</div>
        <button onClick={() => void load(1)} class="rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white dark:bg-white dark:text-slate-950">搜索</button>
      </div>
      {published && <div class="mt-4 flex flex-col gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800 sm:flex-row sm:items-center sm:justify-between dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300"><div><div class="font-semibold">资料包已按 Document Version 去重生成</div><div class="mt-1 text-xs opacity-80">{published.manifest?.versions?.length || 0} 个原始版本 · 有效至 {formatDate(published.expires_at)}</div></div><a href={published.download_url} target="_blank" rel="noreferrer" class="inline-flex items-center gap-2 rounded-lg bg-emerald-700 px-3 py-2 text-xs font-semibold text-white"><Icon name="download" size={14}/>下载 ZIP</a></div>}
    </section>

    <section class="grid gap-3">
      {loading ? <LoadingBlock rows={7}/> : evidences.map(item => <article key={item.evidence_id} class={`rounded-2xl border bg-white p-4 shadow-sm transition dark:bg-slate-900 ${selected.has(item.evidence_id) ? 'border-emerald-300 ring-2 ring-emerald-100 dark:border-emerald-800 dark:ring-emerald-950' : 'border-slate-200/80 dark:border-slate-800'}`}>
        <div class="flex flex-col gap-4 lg:flex-row lg:items-center">
          <div class="flex items-start gap-3 lg:w-[32%]"><button disabled={!item.active} onClick={() => toggle(item)} class={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded border ${selected.has(item.evidence_id) ? 'border-emerald-600 bg-emerald-600 text-white' : 'border-slate-300 text-transparent dark:border-slate-600'} disabled:cursor-not-allowed disabled:opacity-30`} aria-label="选择 Evidence"><Icon name="check" size={12}/></button><div class="min-w-0"><div class="flex items-center gap-2"><div class="truncate text-sm font-semibold">{item.logical_name}</div><StatusBadge value={item.active ? 'ACTIVE' : 'INACTIVE'}/></div><div class="mt-1 truncate text-xs text-slate-400">{item.original_filename} · v{item.version_no}</div></div></div>
          <div class="min-w-0 flex-1"><p class="line-clamp-2 text-sm leading-6 text-slate-600 dark:text-slate-300">{item.excerpt || '—'}</p><div class="mt-2 flex flex-wrap gap-2 text-[11px] text-slate-400"><span>Evidence {shortID(item.evidence_id)}</span><span>Block {shortID(item.block_id)}</span><span>{item.block_type}</span><span>{locationLabel(item.raw_location)}</span></div></div>
          <div class="flex shrink-0 items-center gap-2"><span class="hidden text-xs text-slate-400 xl:block">{formatDate(item.created_at)}</span><SecondaryButton onClick={() => void open(item.evidence_id)} icon="evidence">查看原文</SecondaryButton></div>
        </div>
      </article>)}
      {!loading && evidences.length === 0 && <EmptyState icon="evidence" title="没有匹配的 Evidence" description="Evidence 会在 Raw ingestion 成功后创建，并与精确 Document Version / Block / Location 绑定。" />}
    </section>
    <div class="flex items-center justify-between rounded-xl border border-slate-200 bg-white px-4 py-3 text-xs text-slate-400 dark:border-slate-800 dark:bg-slate-900"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => void load(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => void load(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>
  </div>;
}

function EvidenceMetric({ label, value, icon, tone }: { label: string; value: string; icon: 'shield' | 'layers' | 'graph' | 'activity'; tone: 'emerald' | 'blue' | 'amber' | 'violet' }) {
  const toneClass = { emerald: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-950/40 dark:text-emerald-300', blue: 'text-blue-600 bg-blue-50 dark:bg-blue-950/40 dark:text-blue-300', amber: 'text-amber-600 bg-amber-50 dark:bg-amber-950/40 dark:text-amber-300', violet: 'text-violet-600 bg-violet-50 dark:bg-violet-950/40 dark:text-violet-300' }[tone];
  return <div class="flex items-center gap-4 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900"><div class={`flex h-11 w-11 items-center justify-center rounded-xl ${toneClass}`}><Icon name={icon} size={19}/></div><div><div class="text-xl font-semibold">{value}</div><div class="text-xs text-slate-400">{label}</div></div></div>;
}
function locationLabel(location: Record<string, unknown>) { if (!location) return 'Location —'; const parts: string[] = []; if (location.page_start != null) parts.push(`Page ${Number(location.page_start) + 1}`); else if (location.page != null) parts.push(`Page ${Number(location.page) + 1}`); if (location.block_no != null) parts.push(`Block ${String(location.block_no)}`); if (location.slide_no != null) parts.push(`Slide ${String(location.slide_no)}`); if (location.sheet_name) parts.push(`Sheet ${String(location.sheet_name)}`); return parts.length ? parts.join(' · ') : 'Raw location'; }
