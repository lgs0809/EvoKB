import { useEffect, useMemo, useState } from 'preact/hooks';
import { askEnterpriseKnowledge, fetchEnterpriseFacets, packageEnterpriseSources, type EnterpriseAskResponse, type EnterpriseEvidence, type EnterpriseFacets, type EnterpriseSearchFilters } from '../lib/api';
import { KnowledgeScope } from './KnowledgeScope';
import { EmptyState, Icon, SecondaryButton, SectionTitle } from './ui';

const RECENT_KEY = 'enterprise-kb-recent-questions';

export function AskPage({ onOpenEvidence, onError }: { onOpenEvidence: (evidence: EnterpriseEvidence) => void; onError: (message: string) => void }) {
  const [question, setQuestion] = useState('');
  const [result, setResult] = useState<EnterpriseAskResponse | null>(null);
  const [facets, setFacets] = useState<EnterpriseFacets>({ companies: [], source_departments: [], document_types: [], material_categories: [] });
  const [filters, setFilters] = useState<EnterpriseSearchFilters>({});
  const [busy, setBusy] = useState(false);
  const [packageBusy, setPackageBusy] = useState(false);
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
    <section class="overflow-hidden rounded-3xl border border-blue-100 bg-white shadow-sm dark:border-blue-950/70 dark:bg-slate-900">
      <div class="border-b border-blue-100 bg-gradient-to-br from-blue-50 via-white to-sky-50 px-5 py-8 text-slate-900 dark:border-blue-950/70 dark:from-blue-950/50 dark:via-slate-900 dark:to-slate-900 dark:text-slate-100 sm:px-8 sm:py-10">
        <div class="mx-auto max-w-4xl">
          <h2 class="text-2xl font-semibold tracking-tight sm:text-3xl">想了解什么？</h2>
          <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">输入问题即可查询企业知识。</p>
          <div class="mt-6 rounded-2xl border border-blue-200 bg-white p-2 shadow-lg shadow-blue-100/40 dark:border-blue-900/70 dark:bg-slate-950/60 dark:shadow-none">
            <textarea value={question} onInput={event => setQuestion((event.target as HTMLTextAreaElement).value)} onKeyDown={event => { if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void ask(); }} placeholder="例如：这份合同的金额是多少，由谁签署？" rows={3} class="w-full resize-none bg-transparent px-3 py-2 text-base text-slate-800 outline-none placeholder:text-slate-400 dark:text-slate-100" />
            <div class="flex flex-wrap items-center justify-end gap-3 border-t border-blue-100 px-2 pt-2 dark:border-blue-950/70">
              <button onClick={() => void ask()} disabled={busy || !question.trim()} class="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm shadow-blue-600/15 transition hover:bg-blue-700 disabled:opacity-50 dark:bg-blue-500 dark:hover:bg-blue-400">{busy ? '正在查询…' : '查询'}</button>
            </div>
          </div>
          <div class="mt-4"><KnowledgeScope facets={facets} filters={filters} onChange={setFilters}/></div>
        </div>
      </div>

      <div class="p-5 sm:p-8">
        {!result && !busy && <div class="mx-auto max-w-4xl">
          {recent.length > 0 ? <div><SectionTitle title="最近查询"/><div class="mt-4 flex flex-wrap gap-2">{recent.map(item => <button key={item} onClick={() => void ask(item)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600 hover:border-blue-300 hover:text-blue-700 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-300">{item}</button>)}</div></div> : <EmptyState icon="search" title="输入问题开始查询" description="查询结果会附带参考资料。"/>}
        </div>}
        {busy && <div class="mx-auto flex min-h-64 max-w-4xl items-center justify-center"><div class="text-center text-sm text-slate-400"><Icon name="refresh" size={24} class="mx-auto mb-3 animate-spin"/>正在查询…</div></div>}
        {result && !busy && <div class="mx-auto max-w-5xl space-y-7">
          <section class="rounded-2xl border border-slate-200 bg-slate-50/70 p-5 dark:border-slate-800 dark:bg-slate-950/50 sm:p-6">
            <div class="mb-4 flex flex-wrap items-center justify-between gap-3"><div class="text-sm font-semibold">回答</div><div class="flex items-center gap-2">{result.evidence_only && <span class="rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">相关资料</span>}<SecondaryButton onClick={() => void downloadSources()} disabled={!evidenceIds.length || packageBusy} icon="download">{packageBusy ? '正在打包…' : '下载参考资料'}</SecondaryButton></div></div>
            <div class="whitespace-pre-wrap text-[15px] leading-8 text-slate-700 dark:text-slate-200">{result.answer}</div>
          </section>

          <section>
            <SectionTitle title={`参考资料 ${result.citations.length} 条`}/>
            <div class="mt-4 grid gap-3 lg:grid-cols-2">{result.citations.map(citation => <button key={`${citation.index}-${citation.evidence.evidence_id}`} onClick={() => onOpenEvidence(citation.evidence)} class="group rounded-2xl border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-blue-300 dark:border-slate-800 dark:bg-slate-900">
              <div class="flex items-start justify-between gap-3"><span class="flex h-7 min-w-7 items-center justify-center rounded-lg bg-blue-600 px-2 text-xs font-semibold text-white dark:bg-blue-500">[{citation.index}]</span><Icon name="arrow" size={15} class="text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-blue-500"/></div>
              <div class="mt-3 line-clamp-4 text-sm leading-6 text-slate-600 dark:text-slate-300">{citation.evidence.raw_content}</div>
              <div class="mt-3 text-[11px] font-medium text-blue-600 dark:text-blue-400">查看原文</div>
            </button>)}</div>
          </section>
        </div>}
      </div>
    </section>
  </div>;
}
