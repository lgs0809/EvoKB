import { useEffect, useMemo, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, fetchEnterpriseWiki, type EnterpriseEvidence, type EnterpriseWiki } from '../lib/api';
import { EmptyState, Icon, StatusBadge, shortID } from './ui';

export function WikiPage({ wikis, onOpenEvidence, onError }: {
  wikis: EnterpriseWiki[];
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [selectedKey, setSelectedKey] = useState('');
  const [wiki, setWiki] = useState<EnterpriseWiki | null>(null);
  const [loading, setLoading] = useState(false);

  const filtered = useMemo(() => wikis.filter(item => !query.trim() || `${item.title} ${item.wiki_type}`.toLowerCase().includes(query.trim().toLowerCase())), [wikis, query]);
  const sections = useMemo(() => {
    const grouped = new Map<string, NonNullable<EnterpriseWiki['claims']>>();
    for (const claim of wiki?.claims || []) {
      const key = claim.section || 'Overview';
      const items = grouped.get(key) || [];
      items.push(claim);
      grouped.set(key, items);
    }
    return Array.from(grouped.entries());
  }, [wiki]);

  const openWiki = async (item: EnterpriseWiki) => {
    setSelectedKey(`${item.wiki_id}:${item.wiki_version}`);
    setLoading(true);
    try {
      setWiki(await fetchEnterpriseWiki(item.wiki_id, item.wiki_version));
      onError('');
    } catch (err) {
      onError(String(err));
    } finally {
      setLoading(false);
    }
  };

  const openEvidence = async (id: string) => {
    try {
      onOpenEvidence(await fetchEnterpriseEvidence(id));
      onError('');
    } catch (err) {
      onError(String(err));
    }
  };

  useEffect(() => {
    if (!selectedKey && wikis.length > 0) {
      const active = wikis.find(item => item.lifecycle === 'ACTIVE') || wikis[0];
      void openWiki(active);
    }
  }, [wikis]);

  return <div class="grid gap-5 xl:grid-cols-[320px_minmax(0,1fr)]">
    <aside class="xl:sticky xl:top-28 xl:self-start">
      <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div class="border-b border-slate-100 p-4 dark:border-slate-800">
          <div class="text-xs font-semibold uppercase tracking-[0.14em] text-violet-600 dark:text-violet-400">Enterprise encyclopedia</div>
          <div class="relative mt-3"><Icon name="search" size={15} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} placeholder="搜索 Wiki" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-violet-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        </div>
        <div class="max-h-[calc(100vh-220px)] space-y-1 overflow-y-auto p-2">
          {filtered.map(item => {
            const active = `${item.wiki_id}:${item.wiki_version}` === selectedKey;
            return <button key={`${item.wiki_id}:${item.wiki_version}`} onClick={() => void openWiki(item)} class={`w-full rounded-xl p-3 text-left transition ${active ? 'bg-slate-950 text-white dark:bg-white dark:text-slate-950' : 'hover:bg-slate-50 dark:hover:bg-slate-800'}`}>
              <div class="flex items-start gap-3"><div class={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${active ? 'bg-white/10 dark:bg-slate-950/10' : 'bg-violet-50 text-violet-600 dark:bg-violet-950/40 dark:text-violet-300'}`}><Icon name="wiki" size={15}/></div><div class="min-w-0 flex-1"><div class="truncate text-sm font-semibold">{item.title}</div><div class={`mt-1 truncate text-[11px] ${active ? 'text-white/55 dark:text-slate-950/55' : 'text-slate-400'}`}>{item.wiki_type} · v{item.wiki_version}</div></div></div>
            </button>;
          })}
          {filtered.length === 0 && <div class="px-3 py-8 text-center text-sm text-slate-400">暂无匹配 Wiki</div>}
        </div>
      </div>
    </aside>

    <main>
      {!wiki && !loading && <EmptyState icon="wiki" title="暂无企业 Wiki" description="当高价值 Document Version 通过 Summary Grounding/Coverage 并完成 Wiki 编译后，这里会展示 Claim-first 企业百科。" />}
      {loading && !wiki && <div class="rounded-2xl border border-slate-200 bg-white p-12 text-center text-sm text-slate-400 dark:border-slate-800 dark:bg-slate-900">正在加载 Wiki…</div>}
      {wiki && <article class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <header class="border-b border-slate-100 bg-gradient-to-br from-violet-50 via-white to-blue-50/60 px-5 py-7 sm:px-8 dark:border-slate-800 dark:from-violet-950/25 dark:via-slate-900 dark:to-blue-950/20">
          <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div><div class="text-xs font-semibold uppercase tracking-[0.16em] text-violet-600 dark:text-violet-400">{wiki.wiki_type}</div><h2 class="mt-2 text-2xl font-semibold tracking-tight sm:text-3xl">{wiki.title}</h2><div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400"><span>Wiki {shortID(wiki.wiki_id)}</span><span>·</span><span>Version {wiki.wiki_version}</span>{wiki.canonical_subject_id && <><span>·</span><span>Canonical {shortID(wiki.canonical_subject_id)}</span></>}</div></div>
            <StatusBadge value={wiki.lifecycle}/>
          </div>
          <div class="mt-6 grid grid-cols-3 gap-3 sm:max-w-md"><WikiStat label="Claims" value={wiki.claims?.length || 0}/><WikiStat label="Grounded" value={(wiki.claims || []).filter(item => item.grounding_status === 'PASS').length}/><WikiStat label="Evidence links" value={(wiki.claims || []).reduce((sum, item) => sum + (item.evidence_ids?.length || 0), 0)}/></div>
        </header>

        <div class="grid lg:grid-cols-[190px_minmax(0,1fr)]">
          <nav class="hidden border-r border-slate-100 p-5 lg:block dark:border-slate-800"><div class="sticky top-28"><div class="mb-3 text-[10px] font-semibold uppercase tracking-[0.14em] text-slate-400">Contents</div><div class="space-y-1">{sections.map(([section]) => <a href={`#wiki-${slug(section)}`} class="block rounded-lg px-2 py-1.5 text-xs text-slate-500 hover:bg-slate-50 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white">{section}</a>)}</div></div></nav>
          <div class="p-5 sm:p-8">
            {sections.map(([section, claims]) => <section id={`wiki-${slug(section)}`} key={section} class="mb-9 scroll-mt-28 last:mb-0">
              <div class="mb-4 flex items-center gap-3"><div class="h-5 w-1 rounded-full bg-violet-500"/><h3 class="text-lg font-semibold tracking-tight">{section}</h3><span class="text-xs text-slate-400">{claims.length} claims</span></div>
              <div class="space-y-3">{claims.map(claim => <div key={claim.claim_id} class="rounded-2xl border border-slate-200/80 p-4 transition hover:border-slate-300 dark:border-slate-800 dark:hover:border-slate-700">
                <div class="flex gap-3"><div class="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-300"><Icon name="check" size={14}/></div><div class="min-w-0 flex-1"><p class="text-sm leading-7 text-slate-700 dark:text-slate-200">{claim.claim_text}</p><div class="mt-3 flex flex-wrap gap-2"><StatusBadge value={claim.grounding_status}/><StatusBadge value={claim.temporal_status}/>{(claim.fact_ids || []).map(id => <span key={id} class="rounded-full bg-blue-50 px-2.5 py-1 text-[11px] font-medium text-blue-700 dark:bg-blue-950/40 dark:text-blue-300">Fact {shortID(id)}</span>)}</div></div></div>
                <div class="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800"><div class="mb-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-400">Citations</div><div class="flex flex-wrap gap-2">{(claim.evidence_ids || []).map((id, index) => <button key={id} onClick={() => void openEvidence(id)} class="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-xs font-medium text-slate-600 hover:border-violet-300 hover:bg-violet-50 hover:text-violet-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:border-violet-800 dark:hover:bg-violet-950/30 dark:hover:text-violet-300"><Icon name="link" size={13}/>[{index + 1}] Evidence {shortID(id)}</button>)}{(!claim.evidence_ids || claim.evidence_ids.length === 0) && <span class="text-xs text-rose-500">无 Raw Evidence —— 该 Claim 不应进入 ACTIVE Wiki</span>}</div></div>
              </div>)}</div>
            </section>)}
            {sections.length === 0 && <EmptyState icon="wiki" title="Wiki 尚无 Claims" description="ACTIVE Wiki 应由经过 Grounding 与 Coverage 的 Claim 组成。" />}
          </div>
        </div>
      </article>}
    </main>
  </div>;
}

function WikiStat({ label, value }: { label: string; value: number }) {
  return <div class="rounded-xl border border-white/60 bg-white/70 p-3 text-center shadow-sm backdrop-blur dark:border-slate-700 dark:bg-slate-900/60"><div class="text-lg font-semibold">{value}</div><div class="mt-0.5 text-[10px] uppercase tracking-wide text-slate-400">{label}</div></div>;
}

function slug(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]+/g, '-').replace(/^-|-$/g, '') || 'section';
}
