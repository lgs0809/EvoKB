import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, fetchEnterpriseWiki, fetchEnterpriseWikis, type EnterpriseEvidence, type EnterpriseWiki } from '../lib/api';
import { EmptyState, Icon, LoadingBlock, StatusBadge, shortID } from './ui';
import { usePageRefresh } from './PageRefresh';
import { KnowledgeEntryBrowser } from './KnowledgeEntryBrowser';

const FAVORITES_KEY = 'enterprise-kb-favorite-wikis';
const wikiTypeLabel = (type: string) => ({ CONTRACT: '合同履约专题', COMPANY: '主体专题', LEGAL_CASE: '法律事项专题', PROJECT: '项目专题', GENERIC_TOPIC: '知识专题', TOPIC: '知识专题' } as Record<string,string>)[type] || '知识专题';

export function WikiPage({ onOpenEvidence, onError }: {
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [mode, setMode] = useState<'entries' | 'wikis'>('entries');
  const [requestedWiki, setRequestedWiki] = useState('');
  return <div class="space-y-5">
    <section class="flex flex-col gap-3 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between dark:border-slate-800 dark:bg-slate-900"><div><h2 class="text-base font-semibold">企业百科</h2><p class="mt-1 text-xs leading-5 text-slate-500">从轻量条目查看已整理主题，再阅读已形成的完整百科。</p></div><div class="flex gap-2">{(['entries', 'wikis'] as const).map(value => <button key={value} onClick={() => { setMode(value); setRequestedWiki(''); }} class={`rounded-xl px-4 py-2 text-sm font-semibold ${mode === value ? 'bg-blue-600 text-white dark:bg-blue-500' : 'bg-slate-100 text-slate-500 hover:bg-blue-50 dark:bg-slate-800 dark:text-slate-300'}`}>{value === 'entries' ? '知识条目' : '完整百科'}</button>)}</div></section>
    {mode === 'entries' ? <KnowledgeEntryBrowser onOpenEvidence={onOpenEvidence} onError={onError} onOpenWiki={id => { setRequestedWiki(id); setMode('wikis'); }} /> : <FullWikiPage onOpenEvidence={onOpenEvidence} onError={onError} requestedWiki={requestedWiki} />}
  </div>;
}

function FullWikiPage({ onOpenEvidence, onError, requestedWiki }: {
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
  requestedWiki: string;
}) {
  const [query, setQuery] = useState('');
  const [lifecycle, setLifecycle] = useState('ACTIVE');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [wikis, setWikis] = useState<EnterpriseWiki[]>([]);
  const [selectedKey, setSelectedKey] = useState('');
  const [wiki, setWiki] = useState<EnterpriseWiki | null>(null);
  const [wikiVersions, setWikiVersions] = useState<EnterpriseWiki[]>([]);
  const [previousWiki, setPreviousWiki] = useState<EnterpriseWiki | null>(null);
  const [favorites, setFavorites] = useState<string[]>(() => {
    try { return JSON.parse(localStorage.getItem(FAVORITES_KEY) || '[]'); } catch { return []; }
  });
  const [favoritesOnly, setFavoritesOnly] = useState(false);
  const [loadingList, setLoadingList] = useState(true);
  const [loading, setLoading] = useState(false);
  const pageSize = 30;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const openRequest = useRef(0);
  const listRequest = useRef(0);

  const sections = useMemo(() => {
    const grouped = new Map<string, NonNullable<EnterpriseWiki['claims']>>();
    for (const claim of wiki?.claims || []) {
      const key = claim.section || '概要';
      const items = grouped.get(key) || [];
      items.push(claim);
      grouped.set(key, items);
    }
    return Array.from(grouped.entries());
  }, [wiki]);

  const openWiki = async (item: EnterpriseWiki) => {
    const request = ++openRequest.current;
    const key = `${item.wiki_id}:${item.wiki_version}`;
    if (key !== selectedKey) { setWiki(null); setPreviousWiki(null); setWikiVersions([]); }
    setSelectedKey(key);
    setLoading(true);
    try {
      const [detail, historyData] = await Promise.all([
        fetchEnterpriseWiki(item.wiki_id, item.wiki_version),
        fetchEnterpriseWikis({ wiki_id: item.wiki_id, lifecycle: 'ALL', page: 1, page_size: 100 }),
      ]);
      const history = (historyData.wikis || []).sort((a, b) => b.wiki_version - a.wiki_version);
      const previous = history.find(version => version.wiki_version < item.wiki_version);
      const previousDetail = previous ? await fetchEnterpriseWiki(previous.wiki_id, previous.wiki_version) : null;
      if (request !== openRequest.current) return;
      setWiki(detail);
      setWikiVersions(history);
      setPreviousWiki(previousDetail);
      onError('');
    } catch (err) {
      if (request !== openRequest.current) return;
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      if (request === openRequest.current) setLoading(false);
    }
  };

  const load = async (nextPage = page, nextLifecycle = lifecycle, favoriteMode = favoritesOnly) => {
    const request = ++listRequest.current;
    setLoadingList(true);
    try {
      let items: EnterpriseWiki[] = [];
      let nextTotal = 0;
      if (requestedWiki) {
        const data = await fetchEnterpriseWikis({ wiki_id: requestedWiki, page: 1, page_size: pageSize, lifecycle: nextLifecycle });
        items = data.wikis || [];
        nextTotal = data.total || 0;
        nextPage = 1;
      } else if (favoriteMode) {
        const favoriteResults = await Promise.all(favorites.slice(0, 50).map(id => fetchEnterpriseWikis({ wiki_id: id, page: 1, page_size: 1, lifecycle: nextLifecycle })));
        items = favoriteResults.flatMap(result => result.wikis || []).filter(item => !query.trim() || `${item.title} ${item.wiki_type}`.toLowerCase().includes(query.trim().toLowerCase()));
        nextTotal = items.length;
        nextPage = 1;
      } else {
        const data = await fetchEnterpriseWikis({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, lifecycle: nextLifecycle });
        items = data.wikis || [];
        nextTotal = data.total || 0;
      }
      if (request !== listRequest.current) return;
      setWikis(items);
      setTotal(nextTotal);
      setPage(nextPage);
      if (items.length > 0) {
        const current = items.find(item => item.wiki_id === requestedWiki) || items.find(item => `${item.wiki_id}:${item.wiki_version}` === selectedKey) || items[0];
        await openWiki(current);
      } else {
        openRequest.current += 1;
        setWiki(null);
        setSelectedKey('');
        setPreviousWiki(null); setWikiVersions([]); setLoading(false);
      }
      if (request === listRequest.current) onError('');
    } catch (err) {
      if (request !== listRequest.current) return;
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      if (request === listRequest.current) setLoadingList(false);
    }
  };

  const openEvidence = async (id: string) => {
    try {
      onOpenEvidence(await fetchEnterpriseEvidence(id));
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  const toggleFavorite = (wikiID: string) => {
    setFavorites(prev => {
      const next = prev.includes(wikiID) ? prev.filter(id => id !== wikiID) : [wikiID, ...prev].slice(0, 50);
      localStorage.setItem(FAVORITES_KEY, JSON.stringify(next));
      return next;
    });
  };

  useEffect(() => {
    void load(1, 'ACTIVE', false);
    return () => { openRequest.current += 1; listRequest.current += 1; };
  }, []);
  usePageRefresh(() => { void load(page, lifecycle, favoritesOnly); });

  return <div class="grid gap-5 xl:grid-cols-[340px_minmax(0,1fr)]">
    <aside class="xl:sticky xl:top-28 xl:self-start">
      <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div class="border-b border-slate-100 p-4 dark:border-slate-800">
          <div class="text-xs font-semibold tracking-[0.14em] text-violet-600 dark:text-violet-400">企业百科</div>
          <div class="relative mt-3"><Icon name="search" size={15} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索百科标题或类型" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
          <div class="mt-3 flex flex-wrap gap-2">{['ACTIVE','STALE','ALL'].map(item => <button key={item} onClick={() => { setLifecycle(item); void load(1, item); }} class={`rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition ${lifecycle === item ? 'bg-blue-600 text-white shadow-sm shadow-blue-600/15 dark:bg-blue-500' : 'bg-slate-100 text-slate-500 hover:bg-blue-50 hover:text-blue-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-blue-950/30 dark:hover:text-blue-300'}`}>{({ ACTIVE: '可用', STALE: '需要更新', ALL: '全部' } as Record<string, string>)[item]}</button>)}<button onClick={() => { const next = !favoritesOnly; setFavoritesOnly(next); void load(1, lifecycle, next); }} class={`rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition ${favoritesOnly ? 'bg-blue-600 text-white shadow-sm shadow-blue-600/15 dark:bg-blue-500' : 'bg-slate-100 text-slate-500 hover:bg-blue-50 hover:text-blue-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-blue-950/30 dark:hover:text-blue-300'}`}>★ 收藏 {favorites.length}</button><button onClick={() => void load(1)} class="ml-auto rounded-lg border border-blue-200 bg-blue-50/70 px-2.5 py-1.5 text-[11px] font-semibold text-blue-700 transition hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60">搜索</button></div>
        </div>
        {loadingList ? <div class="p-3"><LoadingBlock rows={6}/></div> : <div class="max-h-[calc(100vh-300px)] space-y-1 overflow-y-auto p-2">
          {wikis.map(item => {
            const active = `${item.wiki_id}:${item.wiki_version}` === selectedKey;
            return <button key={`${item.wiki_id}:${item.wiki_version}`} onClick={() => void openWiki(item)} class={`w-full rounded-xl p-3 text-left transition ${active ? 'bg-blue-600 text-white shadow-sm shadow-blue-600/15 dark:bg-blue-500' : 'hover:bg-blue-50 hover:text-blue-700 dark:hover:bg-blue-950/30 dark:hover:text-blue-300'}`}>
              <div class="flex items-start gap-3"><div class={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${active ? 'bg-white/15 text-white' : 'bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300'}`}><Icon name="wiki" size={15}/></div><div class="min-w-0 flex-1"><div class="truncate text-sm font-semibold">{item.title}</div><div class={`mt-1 truncate text-[11px] ${active ? 'text-white/65' : 'text-slate-400'}`}>{wikiTypeLabel(item.wiki_type)} · 版本 {item.wiki_version}</div></div></div>
            </button>;
          })}
          {wikis.length === 0 && <div class="px-3 py-8 text-center text-sm text-slate-400">暂无匹配百科</div>}
        </div>}
        <div class="flex items-center justify-between border-t border-slate-100 px-3 py-3 text-[11px] text-slate-400 dark:border-slate-800"><span>{total} 篇百科 · {page}/{pageCount}</span><div class="flex gap-1"><button disabled={page <= 1} onClick={() => void load(page - 1)} class="rounded-lg border border-slate-200 px-2 py-1 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => void load(page + 1)} class="rounded-lg border border-slate-200 px-2 py-1 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>
      </div>
    </aside>

    <section>
      {!wiki && !loading && <EmptyState icon="wiki" title="暂无企业百科" description="百科生成完成后会在这里显示。" />}
      {loading && !wiki && <div class="rounded-2xl border border-slate-200 bg-white p-12 text-center text-sm text-slate-400 dark:border-slate-800 dark:bg-slate-900">正在加载企业百科…</div>}
      {wiki && <article class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <header class="border-b border-slate-100 bg-gradient-to-br from-violet-50 via-white to-blue-50/60 px-5 py-7 sm:px-8 dark:border-slate-800 dark:from-violet-950/25 dark:via-slate-900 dark:to-blue-950/20">
          <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div><div class="text-xs font-semibold tracking-[0.16em] text-violet-600 dark:text-violet-400">{wikiTypeLabel(wiki.wiki_type)}</div><h2 class="mt-2 text-2xl font-semibold tracking-tight sm:text-3xl">{wiki.title}</h2><div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400"><span>百科 {shortID(wiki.wiki_id)}</span><span>·</span><span>版本 {wiki.wiki_version}</span>{wiki.canonical_subject_id && <><span>·</span><span>标准主题 {shortID(wiki.canonical_subject_id)}</span></>}</div></div>
            <div class="flex items-center gap-2"><button onClick={() => toggleFavorite(wiki.wiki_id)} class={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition ${favorites.includes(wiki.wiki_id) ? 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-900/70 dark:bg-blue-950/40 dark:text-blue-300' : 'border-blue-100 bg-white/70 text-blue-600 hover:bg-blue-50 dark:border-blue-950 dark:bg-slate-900/60 dark:text-blue-300 dark:hover:bg-blue-950/30'}`}>{favorites.includes(wiki.wiki_id) ? '★ 已收藏' : '☆ 收藏百科'}</button><StatusBadge value={wiki.lifecycle}/></div>
          </div>
          <div class="mt-6 grid grid-cols-3 gap-3 sm:max-w-md"><WikiStat label="知识陈述" value={wiki.claims?.length || 0}/><WikiStat label="事实校验通过" value={(wiki.claims || []).filter(item => item.grounding_status === 'PASS').length}/><WikiStat label="证据引用" value={(wiki.claims || []).reduce((sum, item) => sum + (item.evidence_ids?.length || 0), 0)}/></div>
          {wikiVersions.length > 1 && <div class="mt-5 flex flex-wrap items-center gap-2"><span class="text-[10px] font-semibold tracking-[0.12em] text-slate-400">版本历史</span>{wikiVersions.map(version => <button key={version.wiki_version} onClick={() => void openWiki(version)} class={`rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition ${version.wiki_version === wiki.wiki_version ? 'bg-blue-600 text-white dark:bg-blue-500' : 'border border-blue-100 bg-white/70 text-blue-600 hover:bg-blue-50 dark:border-blue-950 dark:bg-slate-900/60 dark:text-blue-300 dark:hover:bg-blue-950/30'}`}>v{version.wiki_version}</button>)}</div>}
        </header>
        {previousWiki && <WikiVersionDiff current={wiki} previous={previousWiki}/>} 
        <div class="grid lg:grid-cols-[190px_minmax(0,1fr)]">
          <nav class="hidden border-r border-slate-100 p-5 lg:block dark:border-slate-800"><div class="sticky top-28"><div class="mb-3 text-[10px] font-semibold tracking-[0.14em] text-slate-400">目录</div><div class="space-y-1">{sections.map(([section]) => <a href={`#wiki-${slug(section)}`} class="block rounded-lg px-2 py-1.5 text-xs text-slate-500 hover:bg-slate-50 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white">{section}</a>)}</div></div></nav>
          <div class="p-5 sm:p-8">
            {sections.map(([section, claims]) => <section id={`wiki-${slug(section)}`} key={section} class="mb-9 scroll-mt-28 last:mb-0">
              <div class="mb-4 flex items-center gap-3"><div class="h-5 w-1 rounded-full bg-violet-500"/><h3 class="text-lg font-semibold tracking-tight">{section}</h3><span class="text-xs text-slate-400">{claims.length} 条知识陈述</span></div>
              <div class="space-y-3">{claims.map(claim => <div key={claim.claim_id} class="rounded-2xl border border-slate-200/80 p-4 transition hover:border-slate-300 dark:border-slate-800 dark:hover:border-slate-700">
                <div class="flex gap-3"><div class="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-300"><Icon name="check" size={14}/></div><div class="min-w-0 flex-1"><p class="text-sm leading-7 text-slate-700 dark:text-slate-200">{claim.claim_text}</p><div class="mt-3 flex flex-wrap gap-2"><StatusBadge value={claim.grounding_status}/><StatusBadge value={claim.temporal_status}/></div></div></div>
                <div class="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800"><div class="mb-2 text-[10px] font-semibold tracking-[0.12em] text-slate-400">原文依据</div><div class="flex flex-wrap gap-2">{(claim.evidence_ids || []).map((id, index) => <button key={id} onClick={() => void openEvidence(id)} class="inline-flex items-center gap-1.5 rounded-lg border border-blue-100 bg-blue-50/70 px-2.5 py-1.5 text-xs font-medium text-blue-700 transition hover:border-blue-300 hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60"><Icon name="link" size={13}/>[{index + 1}] 证据 {shortID(id)}</button>)}{(!claim.evidence_ids || claim.evidence_ids.length === 0) && <span class="text-xs text-slate-400">暂无原文依据</span>}</div></div>
              </div>)}</div>
            </section>)}
            {sections.length === 0 && <EmptyState icon="wiki" title="暂无可展示内容" description="百科内容生成后会显示在这里。" />}
          </div>
        </div>
      </article>}
    </section>
  </div>;
}

function WikiVersionDiff({ current, previous }: { current: EnterpriseWiki; previous: EnterpriseWiki }) {
  const previousClaims = new Set((previous.claims || []).map(item => item.claim_text.trim()).filter(Boolean));
  const currentClaims = new Set((current.claims || []).map(item => item.claim_text.trim()).filter(Boolean));
  const added = Array.from(currentClaims).filter(text => !previousClaims.has(text));
  const removed = Array.from(previousClaims).filter(text => !currentClaims.has(text));
  if (added.length === 0 && removed.length === 0) return null;
  return <details class="border-b border-slate-100 bg-slate-50/70 dark:border-slate-800 dark:bg-slate-950/40">
    <summary class="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 text-sm font-semibold sm:px-8"><span class="flex items-center gap-2"><Icon name="activity" size={16} class="text-violet-500"/>百科 v{previous.wiki_version} → v{current.wiki_version} 知识变化</span><span class="text-xs font-normal text-slate-400">+{added.length} / -{removed.length}</span></summary>
    <div class="grid gap-4 px-5 pb-5 sm:px-8 lg:grid-cols-2"><DiffColumn title="新增知识陈述" tone="add" items={added}/><DiffColumn title="移除知识陈述" tone="remove" items={removed}/></div>
  </details>;
}

function DiffColumn({ title, tone, items }: { title: string; tone: 'add' | 'remove'; items: string[] }) {
  const classes = tone === 'add' ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/25 dark:text-emerald-300' : 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-900/60 dark:bg-rose-950/25 dark:text-rose-300';
  return <div><div class="mb-2 text-xs font-semibold text-slate-500 dark:text-slate-400">{title}</div><div class="space-y-2">{items.slice(0, 20).map(item => <div key={item} class={`rounded-xl border p-3 text-xs leading-6 ${classes}`}>{tone === 'add' ? '+' : '-'} {item}</div>)}{items.length === 0 && <div class="rounded-xl border border-dashed border-slate-200 p-3 text-xs text-slate-400 dark:border-slate-700">无变化</div>}{items.length > 20 && <div class="text-[11px] text-slate-400">还有 {items.length - 20} 条未展开。</div>}</div></div>;
}

function WikiStat({ label, value }: { label: string; value: number }) { return <div class="rounded-xl border border-white/60 bg-white/70 p-3 text-center shadow-sm backdrop-blur dark:border-slate-700 dark:bg-slate-900/60"><div class="text-lg font-semibold">{value}</div><div class="mt-0.5 text-[10px] uppercase tracking-wide text-slate-400">{label}</div></div>; }
function slug(value: string) { return value.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]+/g, '-').replace(/^-|-$/g, '') || 'section'; }
