import { useEffect, useRef, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, fetchEnterpriseKnowledgeEntries, fetchEnterpriseKnowledgeEntry, type EnterpriseEvidence, type EnterpriseKnowledgeEntry } from '../lib/api';
import { EmptyState, Icon, LoadingBlock, StatusBadge, formatDate } from './ui';
import { usePageRefresh } from './PageRefresh';

const entryTypeLabel = (type: string) => ({ CONTRACT: '合同', PRODUCT: '产品', COMPANY: '主体', ORGANIZATION: '机构', PROJECT: '项目', LEGAL_CASE: '法律事项', PERSON: '人员', GENERIC_TOPIC: '专题', TOPIC: '专题' } as Record<string, string>)[type] || '知识条目';

export function KnowledgeEntryBrowser({ onOpenEvidence, onOpenWiki, onError }: {
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onOpenWiki: (entryId: string) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [stage, setStage] = useState('ALL');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [entries, setEntries] = useState<EnterpriseKnowledgeEntry[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [entry, setEntry] = useState<EnterpriseKnowledgeEntry | null>(null);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingEntry, setLoadingEntry] = useState(false);
  const request = useRef(0);
  const detailRequest = useRef(0);
  const pageSize = 30;
  const pages = Math.max(1, Math.ceil(total / pageSize));

  const open = async (item: EnterpriseKnowledgeEntry) => {
    const id = ++detailRequest.current;
    setSelectedId(item.entry_id);
    setEntry(null);
    setLoadingEntry(true);
    try {
      const detail = await fetchEnterpriseKnowledgeEntry(item.entry_id);
      if (id !== detailRequest.current) return;
      setEntry(detail); onError('');
    } catch (error) {
      if (id === detailRequest.current) onError(error instanceof Error ? error.message : String(error));
    } finally {
      if (id === detailRequest.current) setLoadingEntry(false);
    }
  };

  const load = async (nextPage = page, nextStage = stage) => {
    const id = ++request.current;
    setLoadingList(true);
    // No old prose remains visible while the new authority-filtered result is being read.
    detailRequest.current += 1; setEntry(null); setLoadingEntry(false);
    try {
      const result = await fetchEnterpriseKnowledgeEntries({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, stage: nextStage });
      if (id !== request.current) return;
      setEntries(result.entries); setTotal(result.total); setPage(result.page); onError('');
      const current = result.entries.find(item => item.entry_id === selectedId) || result.entries[0];
      if (current) await open(current); else setSelectedId('');
    } catch (error) {
      if (id === request.current) { setEntries([]); setTotal(0); setSelectedId(''); onError(error instanceof Error ? error.message : String(error)); }
    } finally {
      if (id === request.current) setLoadingList(false);
    }
  };

  const openEvidence = async (id: string) => {
    try { onOpenEvidence(await fetchEnterpriseEvidence(id)); onError(''); }
    catch (error) { onError(error instanceof Error ? error.message : String(error)); }
  };
  useEffect(() => { void load(1, 'ALL'); return () => { request.current += 1; detailRequest.current += 1; }; }, []);
  usePageRefresh(() => { void load(page, stage); });

  return <div class="grid gap-5 xl:grid-cols-[340px_minmax(0,1fr)]">
    <aside class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 xl:sticky xl:top-28 xl:self-start">
      <div class="border-b border-slate-100 p-4 dark:border-slate-800">
        <h2 class="text-sm font-semibold">知识条目</h2>
        <p class="mt-1 text-xs leading-5 text-slate-500">查看已整理的主题、适用范围及当前原文依据。</p>
        <div class="relative mt-3"><Icon name="search" size={15} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" /><input aria-label="搜索知识条目" value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索条目名称、别名或类型" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950" /></div>
        <div class="mt-3 flex flex-wrap gap-2">{(['ALL', 'CONCEPT', 'WIKI'] as const).map(value => <button key={value} onClick={() => { setStage(value); void load(1, value); }} class={`rounded-lg px-2.5 py-1.5 text-xs font-semibold ${value === stage ? 'bg-blue-600 text-white dark:bg-blue-500' : 'bg-slate-100 text-slate-500 hover:bg-blue-50 dark:bg-slate-800 dark:text-slate-300'}`}>{({ ALL: '全部', CONCEPT: '轻量条目', WIKI: '已扩展百科' })[value]}</button>)}<button onClick={() => void load(1)} class="ml-auto rounded-lg border border-blue-200 px-2.5 py-1.5 text-xs font-semibold text-blue-700 dark:border-blue-900 dark:text-blue-300">搜索</button></div>
      </div>
      {loadingList ? <div class="p-4"><LoadingBlock rows={5} /></div> : <div class="max-h-[calc(100vh-340px)] space-y-1 overflow-y-auto p-2">{entries.map(item => <button key={item.entry_id} onClick={() => void open(item)} class={`w-full rounded-xl p-3 text-left transition ${selectedId === item.entry_id ? 'bg-blue-600 text-white dark:bg-blue-500' : 'hover:bg-blue-50 dark:hover:bg-blue-950/30'}`}><div class="flex items-start gap-3"><Icon name="wiki" size={17} class="mt-1 shrink-0" /><div class="min-w-0"><div class="break-words text-sm font-semibold">{item.label}</div><div class={`mt-1 text-xs ${selectedId === item.entry_id ? 'text-white/75' : 'text-slate-400'}`}>{item.stage === 'CONCEPT' ? '轻量条目' : '已扩展百科'} · {entryTypeLabel(item.entry_type)} · {item.source_count} 个来源</div></div></div></button>)}{entries.length === 0 && <div class="px-3 py-8 text-center text-sm text-slate-400">{query.trim() ? '未找到匹配条目' : stage === 'WIKI' ? '当前没有已扩展百科' : '暂无可用知识条目'}</div>}</div>}
      <div class="flex items-center justify-between border-t border-slate-100 p-3 text-xs text-slate-400 dark:border-slate-800"><span>{total} 个条目 · {page}/{pages}</span><div class="flex gap-1"><button disabled={page <= 1 || loadingList} onClick={() => void load(page - 1)} class="rounded-lg border border-slate-200 px-2 py-1 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pages || loadingList} onClick={() => void load(page + 1)} class="rounded-lg border border-slate-200 px-2 py-1 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>
    </aside>
    <section>
      {loadingList || loadingEntry ? <div class="rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900"><LoadingBlock rows={8} /></div> : !entry ? <EmptyState icon="wiki" title={query.trim() ? '未找到匹配条目' : stage === 'WIKI' ? '当前没有已扩展百科' : '暂无可展示条目'} description={query.trim() ? '请调整关键词或切换条目范围，再次搜索。' : stage === 'WIKI' ? '可以切换到轻量条目查看当前已整理的范围与来源。' : '资料完成知识提炼后，当前有效且有原文依据的条目会显示在这里。'} /> : <article class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <header class="border-b border-slate-100 bg-gradient-to-br from-blue-50 via-white to-violet-50 px-5 py-6 sm:px-7 dark:border-slate-800 dark:from-blue-950/25 dark:via-slate-900 dark:to-violet-950/20">
          <div class="flex items-center gap-2"><span class="rounded-full bg-blue-100 px-2.5 py-1 text-xs font-semibold text-blue-700 dark:bg-blue-950/60 dark:text-blue-300">{entry.stage === 'CONCEPT' ? '轻量条目' : '已扩展百科'}</span><StatusBadge value={entry.lifecycle} /></div>
          <h2 class="mt-3 text-2xl font-semibold tracking-tight">{entry.label}</h2>
          <div class="mt-2 text-xs text-slate-400">{entryTypeLabel(entry.entry_type)} · 条目修订 {entry.revision} · 更新于 {formatDate(entry.updated_at)}</div>
          {entry.aliases.length > 0 && <div class="mt-3 flex flex-wrap gap-2">{entry.aliases.map(alias => <span key={alias} class="rounded-lg border border-slate-200 bg-white/70 px-2.5 py-1 text-xs text-slate-500 dark:border-slate-700 dark:bg-slate-900">{alias}</span>)}</div>}
          <p class="mt-4 whitespace-pre-wrap text-sm leading-7 text-slate-700 dark:text-slate-200">{entry.description}</p>
          {entry.stage === 'CONCEPT' ? <p class="mt-3 text-xs leading-6 text-slate-500">当前为轻量条目，根据资料整理范围与来源，关键结论可查看原文依据。完整百科会随实际使用和资料演化进一步形成。</p> : <button onClick={() => onOpenWiki(entry.entry_id)} class="mt-4 rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white dark:bg-blue-500">阅读完整百科</button>}
        </header>
        <div class="space-y-6 p-5 sm:p-7">
          <section><h3 class="text-sm font-semibold">适用范围</h3>{entry.scope.identity_status === 'SCOPE_GROUNDED' && entry.scope.anchors?.length ? <div class="mt-3 flex flex-wrap gap-2">{entry.scope.anchors.map(anchor => <div key={`${anchor.type}:${anchor.value}`} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-950"><span class="mr-2 text-xs text-slate-400">{entryTypeLabel(anchor.type)}</span>{anchor.value}</div>)}</div> : <p class="mt-2 text-sm leading-6 text-slate-500">范围限定于当前来源资料。尚无足够业务依据将它认定为跨资料的同一主题。</p>}</section>
          <section><div class="flex items-center justify-between"><h3 class="text-sm font-semibold">当前有效来源</h3><span class="text-xs text-slate-400">{entry.source_count} 个资料版本</span></div><div class="mt-3 space-y-3">{entry.sources.map(source => <article key={`${source.concept_id}:${source.version_id}`} class="rounded-xl border border-slate-200 p-4 dark:border-slate-800"><div class="flex items-start gap-2"><Icon name="file" size={16} class="mt-0.5 shrink-0 text-blue-500" /><div><h4 class="text-sm font-semibold">{source.logical_name}</h4><div class="mt-1 text-xs text-slate-400">资料版本 v{source.version_no} · {source.facts.length} 条事实依据</div></div></div><div class="mt-3 space-y-3">{source.facts.map(fact => <div key={fact.fact_id} class="border-t border-slate-100 pt-3 dark:border-slate-800"><p class="text-sm leading-7 text-slate-600 dark:text-slate-300">{fact.fact_text}</p><div class="mt-2 flex flex-wrap gap-2">{fact.evidence_ids.map((id, index) => <button key={id} onClick={() => void openEvidence(id)} class="inline-flex items-center gap-1.5 rounded-lg border border-blue-100 bg-blue-50 px-2.5 py-1.5 text-xs font-medium text-blue-700 dark:border-blue-900 dark:bg-blue-950/30 dark:text-blue-300"><Icon name="link" size={13} />查看原文 {index + 1}</button>)}</div></div>)}</div></article>)}</div></section>
        </div>
      </article>}
    </section>
  </div>;
}
