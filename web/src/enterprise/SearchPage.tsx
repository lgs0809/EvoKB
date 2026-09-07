import { useEffect, useMemo, useState } from 'preact/hooks';
import {
  fetchEnterpriseFacets,
  packageEnterpriseSources,
  downloadEnterprisePackage,
  searchEnterpriseKnowledge,
  type EnterpriseEvidence,
  type EnterpriseFacets,
  type EnterprisePublishedPackage,
  type EnterpriseSearchFilters,
  type EnterpriseSearchResponse,
} from '../lib/api';
import { KnowledgeScope } from './KnowledgeScope';
import { EmptyState, Icon, PrimaryButton, SecondaryButton, SectionTitle } from './ui';

export function SearchPage({ onOpenEvidence, onError }: {
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [filters, setFilters] = useState<EnterpriseSearchFilters>({});
  const [facets, setFacets] = useState<EnterpriseFacets>({ companies: [], source_departments: [], document_types: [], material_categories: [] });
  const [response, setResponse] = useState<EnterpriseSearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [packaging, setPackaging] = useState(false);
  const [published, setPublished] = useState<EnterprisePublishedPackage | null>(null);

  useEffect(() => { fetchEnterpriseFacets().then(setFacets).catch(() => undefined); }, []);

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
      onError(String(err instanceof Error ? err.message : err));
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
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setPackaging(false);
    }
  };

  return <div class="space-y-6">
    <section class="rounded-[28px] border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-7">
      <SectionTitle title="检索测试" description="输入问题，查看检索结果、排序分数和参考资料。" />
      <div class="mt-6 flex gap-2 rounded-2xl border border-slate-200 bg-slate-50 p-2 dark:border-slate-700 dark:bg-slate-950">
        <input autoFocus value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void runSearch(); }} placeholder="输入测试问题，例如：公司签署了哪些重大合同？" class="min-w-0 flex-1 bg-transparent px-3 py-2.5 text-sm outline-none placeholder:text-slate-400" />
        <PrimaryButton onClick={() => void runSearch()} disabled={loading || !query.trim()} icon="test">{loading ? '正在测试…' : '开始测试'}</PrimaryButton>
      </div>
      <div class="mt-4"><KnowledgeScope facets={facets} filters={filters} onChange={setFilters} mode="expanded"/></div>
      <div class="mt-4 text-[11px] text-slate-400">支持查看 Rerank 分数与参考资料。</div>
    </section>

    {!response && !loading && <EmptyState icon="test" title="输入问题开始测试" description="测试结果会显示排序分数和参考资料。" />}

    {response && <section class="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div class="space-y-4">
        <SectionTitle title={`${response.results?.length || 0} 条结果`} description={`查询记录 ${response.search_id || '—'}`} />
        {(response.results || []).map((result, index) => <article key={`${result.result_type}:${index}`} class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm transition hover:border-slate-300 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-slate-700">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div class="flex items-center gap-2"><ResultTypeBadge value={result.result_type}/><span class="text-xs text-slate-400">最终排名 #{index + 1}</span></div>
            <div class="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-500 dark:bg-slate-800 dark:text-slate-300">Rerank {Number(result.score || 0).toFixed(4)}</div>
          </div>
          <div class="mt-4 whitespace-pre-wrap text-sm leading-7 text-slate-700 dark:text-slate-200">{result.knowledge}</div>
          <div class="mt-5 border-t border-slate-100 pt-4 dark:border-slate-800">
            <div class="mb-2 text-xs font-semibold text-slate-400">参考资料</div>
            <div class="flex flex-wrap gap-2">{(result.evidence || []).map(item => <button key={item.evidence_id} onClick={() => onOpenEvidence(item)} class="inline-flex max-w-full items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-left text-xs text-slate-600 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:border-blue-800 dark:hover:bg-blue-950/40 dark:hover:text-blue-300"><Icon name="file" size={12} class="shrink-0"/><span class="truncate">{item.source_filename || '未知文件'}</span><Icon name="chevron" size={12} class="shrink-0"/></button>)}</div>
          </div>
        </article>)}
        {(response.results || []).length === 0 && <EmptyState icon="test" title="没有找到结果" description="调整知识范围后重试。" />}
      </div>

      <aside class="space-y-4 xl:sticky xl:top-28 xl:self-start">
        <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <div class="font-semibold">参考资料 <span class="ml-1 text-xs font-normal text-slate-400">{evidence.length} 条</span></div>
          <div class="mt-4 space-y-2">{evidence.slice(0, 8).map(item => <button key={item.evidence_id} onClick={() => onOpenEvidence(item)} class="flex w-full items-center justify-between gap-3 rounded-xl bg-slate-50 px-3 py-2.5 text-left text-xs hover:bg-slate-100 dark:bg-slate-950/60 dark:hover:bg-slate-800"><div class="min-w-0 flex-1"><div class="flex items-center gap-1.5 font-semibold text-slate-700 dark:text-slate-200"><Icon name="file" size={12} class="shrink-0 text-slate-400"/><span class="truncate">{item.source_filename || '未知文件'}</span></div><div class="mt-1 truncate text-slate-500 dark:text-slate-400">{item.raw_content || '查看原文'}</div></div><Icon name="chevron" size={13} class="shrink-0 text-slate-400"/></button>)}</div>
          {evidence.length > 0 && <div class="mt-4"><SecondaryButton onClick={() => void packageSources()} disabled={packaging} icon="download">{packaging ? '正在打包…' : '下载参考资料'}</SecondaryButton></div>}
          {published && <div class="mt-4 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-xs text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300"><div class="font-semibold">资料包已生成</div><div class="mt-1">{published.manifest?.versions?.length || 0} 个资料版本</div><a href={published.download_url} onClick={event => { event.preventDefault(); void downloadEnterprisePackage(published.download_url).catch(error => onError(error instanceof Error ? error.message : String(error))); }} class="mt-2 inline-flex items-center gap-1 font-semibold underline underline-offset-2"><Icon name="download" size={13}/>下载 ZIP</a></div>}
        </div>
      </aside>
    </section>}
  </div>;
}

function ResultTypeBadge({ value }: { value: string }) {
  const normalized = (value || 'UNKNOWN').toUpperCase();
  const labels: Record<string, string> = { RAW: '原文', SUMMARY: '摘要', WIKI: '企业百科', GRAPH: '关系图谱' };
  const tones: Record<string, string> = {
    RAW: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
    SUMMARY: 'bg-blue-50 text-blue-700 dark:bg-blue-950/50 dark:text-blue-300',
    WIKI: 'bg-violet-50 text-violet-700 dark:bg-violet-950/50 dark:text-violet-300',
    GRAPH: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
  };
  return <span class={`rounded-full px-2.5 py-1 text-[11px] font-semibold tracking-wide ${tones[normalized] || tones.RAW}`}>{labels[normalized] || normalized}</span>;
}

function cleanFilters(filters: EnterpriseSearchFilters): EnterpriseSearchFilters {
  return Object.fromEntries(Object.entries(filters).filter(([, value]) => Array.isArray(value) ? value.length > 0 : Boolean(value))) as EnterpriseSearchFilters;
}
