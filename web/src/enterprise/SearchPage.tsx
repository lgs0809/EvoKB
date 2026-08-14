import { useMemo, useState } from 'preact/hooks';
import {
  packageEnterpriseSources,
  searchEnterpriseKnowledge,
  type EnterpriseDocumentVersion,
  type EnterpriseEvidence,
  type EnterprisePublishedPackage,
  type EnterpriseSearchFilters,
  type EnterpriseSearchResponse,
} from '../lib/api';
import { EmptyState, Icon, PrimaryButton, SecondaryButton, SectionTitle, shortID } from './ui';

export function SearchPage({ documents, onOpenEvidence, onError }: {
  documents: EnterpriseDocumentVersion[];
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [filters, setFilters] = useState<EnterpriseSearchFilters>({});
  const [response, setResponse] = useState<EnterpriseSearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [packaging, setPackaging] = useState(false);
  const [published, setPublished] = useState<EnterprisePublishedPackage | null>(null);

  const filterOptions = useMemo(() => ({
    companies: unique(documents.map(item => item.company)),
    departments: unique(documents.map(item => item.source_department)),
    types: unique(documents.map(item => item.document_type)),
    categories: unique(documents.map(item => item.material_category)),
  }), [documents]);

  const evidence = useMemo(() => {
    const seen = new Set<string>();
    const out: EnterpriseEvidence[] = [];
    for (const result of response?.results || []) {
      for (const item of result.evidence || []) {
        if (seen.has(item.evidence_id)) continue;
        seen.add(item.evidence_id);
        out.push(item);
      }
    }
    return out;
  }, [response]);

  const runSearch = async () => {
    if (!query.trim()) return;
    setLoading(true);
    setPublished(null);
    try {
      const next = await searchEnterpriseKnowledge(query.trim(), cleanFilters(filters), 8);
      setResponse(next);
      onError('');
    } catch (err) {
      onError(String(err));
    } finally {
      setLoading(false);
    }
  };

  const packageSources = async () => {
    if (!evidence.length) return;
    setPackaging(true);
    try {
      const result = await packageEnterpriseSources(evidence.map(item => item.evidence_id));
      setPublished(result);
      onError('');
    } catch (err) {
      onError(String(err));
    } finally {
      setPackaging(false);
    }
  };

  return <div class="space-y-6">
    <section class="overflow-hidden rounded-[28px] border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div class="border-b border-slate-100 bg-gradient-to-br from-blue-50 via-white to-violet-50 px-5 py-8 sm:px-8 dark:border-slate-800 dark:from-blue-950/30 dark:via-slate-900 dark:to-violet-950/20">
        <div class="mx-auto max-w-4xl text-center">
          <div class="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-slate-950 text-white shadow-lg shadow-slate-900/15 dark:bg-white dark:text-slate-950"><Icon name="search" size={22}/></div>
          <h2 class="text-2xl font-semibold tracking-tight sm:text-3xl">从企业知识中寻找可验证的答案依据</h2>
          <p class="mx-auto mt-3 max-w-2xl text-sm leading-6 text-slate-500 dark:text-slate-400">不是先生成一句“看起来正确”的答案，而是先找出 Raw / Summary / Wiki / Graph 中最相关的知识，再逐条回溯到原始 Evidence。</p>
          <div class="mt-6 flex gap-2 rounded-2xl border border-slate-200 bg-white p-2 shadow-lg shadow-slate-200/40 dark:border-slate-700 dark:bg-slate-950 dark:shadow-none">
            <input autoFocus value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void runSearch(); }} placeholder="例如：公司签署了哪些重大合同？实际控制关系是什么？" class="min-w-0 flex-1 bg-transparent px-3 py-2.5 text-sm outline-none placeholder:text-slate-400" />
            <PrimaryButton onClick={() => void runSearch()} disabled={loading || !query.trim()} icon="search">{loading ? '检索中…' : '检索'}</PrimaryButton>
          </div>
          <div class="mt-3 flex items-center justify-center gap-3 text-xs text-slate-400"><span>BM25 + Vector</span><span>•</span><span>Raw / Summary / Wiki / Graph</span><span>•</span><span>One unified Rerank</span><span>•</span><span>Evidence Gate</span></div>
        </div>
      </div>

      <div class="px-5 py-4 sm:px-8">
        <button onClick={() => setFiltersOpen(value => !value)} class="inline-flex items-center gap-2 text-sm font-medium text-slate-600 hover:text-slate-950 dark:text-slate-300 dark:hover:text-white"><Icon name="filter" size={15}/>{filtersOpen ? '收起 Metadata Filter' : '展开 Metadata Filter'}<Icon name="chevron" size={13} class={filtersOpen ? 'rotate-90 transition-transform' : 'transition-transform'}/></button>
        {filtersOpen && <div class="mt-4 grid gap-3 rounded-2xl bg-slate-50 p-4 sm:grid-cols-2 xl:grid-cols-4 dark:bg-slate-950/60">
          <FilterSelect label="Company" value={filters.company || ''} options={filterOptions.companies} onChange={value => setFilters(prev => ({ ...prev, company: value || undefined }))}/>
          <FilterSelect label="Document Type" value={filters.document_type || ''} options={filterOptions.types} onChange={value => setFilters(prev => ({ ...prev, document_type: value || undefined }))}/>
          <FilterSelect label="Department" value={filters.source_department || ''} options={filterOptions.departments} onChange={value => setFilters(prev => ({ ...prev, source_department: value || undefined }))}/>
          <FilterSelect label="Material Category" value={filters.material_category || ''} options={filterOptions.categories} onChange={value => setFilters(prev => ({ ...prev, material_category: value || undefined }))}/>
        </div>}
      </div>
    </section>

    {!response && !loading && <EmptyState icon="search" title="输入一个企业问题开始检索" description="搜索结果不会绕过 Evidence Resolver；如果高阶知识无法回到 Raw Evidence，它不会出现在最终结果中。" />}

    {response && <section class="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div class="space-y-4">
        <SectionTitle eyebrow="Grounded knowledge" title={`${response.results?.length || 0} 条可信知识结果`} description={`Search ID ${response.search_id || '—'} · 结果按统一 Cross-Encoder Rerank 排序。`} />
        {(response.results || []).map((result, index) => <article key={`${result.result_type}:${index}`} class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm transition hover:border-slate-300 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-slate-700">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div class="flex items-center gap-2"><ResultTypeBadge value={result.result_type}/><span class="text-xs text-slate-400">Rank #{index + 1}</span></div>
            <div class="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-500 dark:bg-slate-800 dark:text-slate-300">Rerank {Number(result.score || 0).toFixed(4)}</div>
          </div>
          <div class="mt-4 whitespace-pre-wrap text-sm leading-7 text-slate-700 dark:text-slate-200">{result.knowledge}</div>
          <div class="mt-5 border-t border-slate-100 pt-4 dark:border-slate-800">
            <div class="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.12em] text-slate-400"><Icon name="shield" size={14}/>Raw Evidence</div>
            <div class="flex flex-wrap gap-2">{(result.evidence || []).map(item => <button key={item.evidence_id} onClick={() => onOpenEvidence(item)} class="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-left text-xs text-slate-600 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:border-blue-800 dark:hover:bg-blue-950/40 dark:hover:text-blue-300"><Icon name="evidence" size={14}/><span>Evidence {shortID(item.evidence_id)}</span><Icon name="chevron" size={12}/></button>)}</div>
          </div>
        </article>)}
        {(response.results || []).length === 0 && <EmptyState icon="search" title="没有找到 Evidence-backed 结果" description="可以缩短问题、调整 Metadata Filter，或者先确认目标文档是否已经进入 ACTIVE Raw 生命周期。" />}
      </div>

      <aside class="space-y-4 xl:sticky xl:top-28 xl:self-start">
        <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <div class="flex items-center gap-3"><div class="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300"><Icon name="shield" size={19}/></div><div><div class="font-semibold">证据链</div><div class="text-xs text-slate-400">{evidence.length} 条唯一 Raw Evidence</div></div></div>
          <div class="mt-4 space-y-2">{evidence.slice(0, 8).map(item => <button key={item.evidence_id} onClick={() => onOpenEvidence(item)} class="flex w-full items-center justify-between gap-3 rounded-xl bg-slate-50 px-3 py-2.5 text-left text-xs hover:bg-slate-100 dark:bg-slate-950/60 dark:hover:bg-slate-800"><div class="min-w-0"><div class="truncate font-medium text-slate-700 dark:text-slate-200">Evidence {shortID(item.evidence_id)}</div><div class="mt-0.5 truncate text-slate-400">Version {shortID(item.version_id)}</div></div><Icon name="chevron" size={13} class="shrink-0 text-slate-400"/></button>)}</div>
          {evidence.length > 0 && <div class="mt-4"><SecondaryButton onClick={() => void packageSources()} disabled={packaging} icon="download">{packaging ? '打包中…' : '打包本次使用资料'}</SecondaryButton></div>}
          {published && <div class="mt-4 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-xs text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300"><div class="font-semibold">资料包已生成</div><div class="mt-1">{published.manifest?.versions?.length || 0} 个精确 Document Version</div><a href={published.download_url} target="_blank" rel="noreferrer" class="mt-2 inline-flex items-center gap-1 font-semibold underline underline-offset-2"><Icon name="download" size={13}/>下载 ZIP</a></div>}
        </div>
        <div class="rounded-2xl border border-blue-200 bg-blue-50 p-4 text-xs leading-6 text-blue-800 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-300"><div class="font-semibold">为什么这里不直接伪造“AI 最终答案”？</div><p class="mt-1">Knowledge Server 的职责是交付经过证据门的 Knowledge + Evidence。最终自然语言回答由上层 Agent 基于这些证据生成，避免把检索层和回答层混成不可审计的黑盒。</p></div>
      </aside>
    </section>}
  </div>;
}

function FilterSelect({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  return <label class="text-xs font-medium text-slate-500"><span class="mb-1.5 block">{label}</span><select value={value} onChange={event => onChange((event.target as HTMLSelectElement).value)} class="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-700 outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"><option value="">全部</option>{options.map(option => <option key={option} value={option}>{option}</option>)}</select></label>;
}

function ResultTypeBadge({ value }: { value: string }) {
  const normalized = (value || 'UNKNOWN').toUpperCase();
  const tones: Record<string, string> = {
    RAW: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
    SUMMARY: 'bg-blue-50 text-blue-700 dark:bg-blue-950/50 dark:text-blue-300',
    WIKI: 'bg-violet-50 text-violet-700 dark:bg-violet-950/50 dark:text-violet-300',
    GRAPH: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
  };
  return <span class={`rounded-full px-2.5 py-1 text-[11px] font-semibold tracking-wide ${tones[normalized] || tones.RAW}`}>{normalized}</span>;
}

function unique(values: Array<string | undefined>) {
  return Array.from(new Set(values.map(value => value?.trim()).filter((value): value is string => Boolean(value)))).sort((a, b) => a.localeCompare(b, 'zh-CN'));
}

function cleanFilters(filters: EnterpriseSearchFilters): EnterpriseSearchFilters {
  return Object.fromEntries(Object.entries(filters).filter(([, value]) => Array.isArray(value) ? value.length > 0 : Boolean(value))) as EnterpriseSearchFilters;
}
