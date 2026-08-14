import { useEffect, useMemo, useState } from 'preact/hooks';
import { askEnterpriseKnowledge, fetchEnterpriseFacets, packageEnterpriseSources, type EnterpriseAskResponse, type EnterpriseEvidence, type EnterpriseFacets, type EnterpriseSearchFilters } from '../lib/api';
import { EmptyState, Icon, SecondaryButton, SectionTitle, StatusBadge, shortID } from './ui';

const RECENT_KEY = 'enterprise-kb-recent-questions';

export function AskPage({ onOpenEvidence, onError }: { onOpenEvidence: (evidence: EnterpriseEvidence) => void; onError: (message: string) => void }) {
  const [question, setQuestion] = useState('');
  const [result, setResult] = useState<EnterpriseAskResponse | null>(null);
  const [facets, setFacets] = useState<EnterpriseFacets>({ companies: [], source_departments: [], document_types: [], material_categories: [] });
  const [filters, setFilters] = useState<EnterpriseSearchFilters>({});
  const [busy, setBusy] = useState(false);
  const [packageBusy, setPackageBusy] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [recent, setRecent] = useState<string[]>(() => {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]').slice(0, 8); } catch { return []; }
  });

  useEffect(() => { fetchEnterpriseFacets().then(setFacets).catch(() => undefined); }, []);
  const evidenceIds = useMemo(() => Array.from(new Set((result?.citations || []).map(item => item.evidence.evidence_id).filter(Boolean))), [result]);

  const ask = async (value = question) => {
    const next = value.trim();
    if (!next) return;
    setQuestion(next);
    setBusy(true);
    try {
      const response = await askEnterpriseKnowledge(next, filters, 8);
      setResult(response);
      const history = [next, ...recent.filter(item => item !== next)].slice(0, 8);
      setRecent(history);
      localStorage.setItem(RECENT_KEY, JSON.stringify(history));
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
  };

  const downloadSources = async () => {
    if (!evidenceIds.length) return;
    setPackageBusy(true);
    try {
      const published = await packageEnterpriseSources(evidenceIds);
      window.open(published.download_url, '_blank', 'noopener,noreferrer');
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setPackageBusy(false);
    }
  };

  return <div class="space-y-6">
    <section class="overflow-hidden rounded-3xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div class="border-b border-slate-100 bg-gradient-to-br from-slate-950 via-slate-900 to-blue-950 px-5 py-8 text-white dark:border-slate-800 sm:px-8 sm:py-10">
        <div class="mx-auto max-w-4xl">
          <div class="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-blue-200"><Icon name="sparkles" size={15}/>Evidence-backed Ask</div>
          <h2 class="mt-3 text-2xl font-semibold tracking-tight sm:text-3xl">问企业知识，而不是猜答案</h2>
          <p class="mt-2 max-w-2xl text-sm leading-6 text-slate-300">系统先执行 Raw / Summary / Wiki / Graph 四路可信检索，再基于可回溯 Evidence 生成回答。没有足够证据时会明确告诉你。</p>
          <div class="mt-6 rounded-2xl border border-white/10 bg-white/10 p-2 shadow-xl backdrop-blur">
            <textarea value={question} onInput={event => setQuestion((event.target as HTMLTextAreaElement).value)} onKeyDown={event => { if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void ask(); }} placeholder="例如：这份合同的金额是多少，由谁签署？" rows={3} class="w-full resize-none bg-transparent px-3 py-2 text-base text-white outline-none placeholder:text-slate-400" />
            <div class="flex flex-wrap items-center justify-between gap-3 border-t border-white/10 px-2 pt-2">
              <button onClick={() => setShowFilters(value => !value)} class="inline-flex items-center gap-2 rounded-lg px-2.5 py-2 text-xs font-semibold text-slate-300 hover:bg-white/10"><Icon name="filter" size={14}/>知识范围</button>
              <button onClick={() => void ask()} disabled={busy || !question.trim()} class="inline-flex items-center gap-2 rounded-xl bg-white px-4 py-2.5 text-sm font-semibold text-slate-950 disabled:opacity-50"><Icon name={busy ? 'refresh' : 'sparkles'} size={16} class={busy ? 'animate-spin' : ''}/>{busy ? '检索并回答…' : '提问'}</button>
            </div>
          </div>
          {showFilters && <FilterGrid facets={facets} filters={filters} onChange={setFilters}/>} 
        </div>
      </div>

      <div class="p-5 sm:p-8">
        {!result && !busy && <div class="mx-auto max-w-4xl">
          {recent.length > 0 ? <div><SectionTitle eyebrow="Recent" title="最近提问" description="只保存在当前浏览器，用来快速回到常用问题。"/><div class="mt-4 flex flex-wrap gap-2">{recent.map(item => <button key={item} onClick={() => void ask(item)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600 hover:border-blue-300 hover:text-blue-700 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-300">{item}</button>)}</div></div> : <EmptyState icon="sparkles" title="从一个真实业务问题开始" description="回答会保留 Citation，点击引用可以继续打开 Raw Evidence 与原文件位置。"/>}
        </div>}
        {busy && <div class="mx-auto flex min-h-64 max-w-4xl items-center justify-center"><div class="text-center text-sm text-slate-400"><Icon name="refresh" size={24} class="mx-auto mb-3 animate-spin"/>正在检索可信知识并组织回答…</div></div>}
        {result && !busy && <div class="mx-auto max-w-5xl space-y-6">
          <section class="rounded-2xl border border-slate-200 bg-slate-50/70 p-5 dark:border-slate-800 dark:bg-slate-950/50 sm:p-6">
            <div class="mb-4 flex flex-wrap items-center justify-between gap-3"><div class="flex items-center gap-2"><div class="flex h-9 w-9 items-center justify-center rounded-xl bg-blue-600 text-white"><Icon name="sparkles" size={18}/></div><div><div class="text-sm font-semibold">企业知识回答</div><div class="text-xs text-slate-400">Search {shortID(result.search_id, 12)}</div></div></div><div class="flex gap-2">{result.evidence_only && <StatusBadge value="EVIDENCE ONLY"/>}<SecondaryButton onClick={() => void downloadSources()} disabled={!evidenceIds.length || packageBusy} icon="download">{packageBusy ? '打包中…' : '打包来源'}</SecondaryButton></div></div>
            <div class="whitespace-pre-wrap text-[15px] leading-8 text-slate-700 dark:text-slate-200">{result.answer}</div>
          </section>

          <section>
            <SectionTitle eyebrow="Citations" title={`引用证据 ${result.citations.length}`} description="每一条引用都绑定精确 Evidence ID、Document Version 和 Raw location。"/>
            <div class="mt-4 grid gap-3 lg:grid-cols-2">{result.citations.map(citation => <button key={`${citation.index}-${citation.evidence.evidence_id}`} onClick={() => onOpenEvidence(citation.evidence)} class="group rounded-2xl border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-blue-300 dark:border-slate-800 dark:bg-slate-900">
              <div class="flex items-start justify-between gap-3"><div class="flex items-center gap-2"><span class="flex h-7 min-w-7 items-center justify-center rounded-lg bg-slate-950 px-2 text-xs font-semibold text-white dark:bg-white dark:text-slate-950">[{citation.index}]</span><StatusBadge value={citation.result_type}/></div><Icon name="arrow" size={15} class="text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-blue-500"/></div>
              <div class="mt-3 line-clamp-4 text-sm leading-6 text-slate-600 dark:text-slate-300">{citation.evidence.raw_content}</div>
              <div class="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-slate-400"><span>Evidence {shortID(citation.evidence.evidence_id, 10)}</span><span>Version {shortID(citation.evidence.version_id, 10)}</span></div>
            </button>)}</div>
          </section>
        </div>}
      </div>
    </section>
  </div>;
}

function FilterGrid({ facets, filters, onChange }: { facets: EnterpriseFacets; filters: EnterpriseSearchFilters; onChange: (filters: EnterpriseSearchFilters) => void }) {
  const options: Array<{ key: keyof EnterpriseSearchFilters; label: string; values: string[] }> = [
    { key: 'company', label: '公司', values: facets.companies || [] },
    { key: 'source_department', label: '部门', values: facets.source_departments || [] },
    { key: 'document_type', label: '文档类型', values: facets.document_types || [] },
    { key: 'material_category', label: '材料分类', values: facets.material_categories || [] },
  ];
  return <div class="mt-4 grid gap-3 rounded-2xl border border-white/10 bg-black/20 p-4 sm:grid-cols-2 lg:grid-cols-4">{options.map(item => <label key={item.key} class="text-xs font-medium text-slate-300">{item.label}<select value={(filters[item.key] as string) || ''} onChange={event => onChange({ ...filters, [item.key]: (event.target as HTMLSelectElement).value || undefined })} class="mt-1.5 w-full rounded-xl border border-white/10 bg-slate-900 px-3 py-2.5 text-sm text-white outline-none"><option value="">全部</option>{item.values.map(value => <option value={value} key={value}>{value}</option>)}</select></label>)}</div>;
}
