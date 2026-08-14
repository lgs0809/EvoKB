import { useMemo, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, fetchEnterpriseGraph, type EnterpriseEvidence, type EnterpriseGraphRelation } from '../lib/api';
import { EmptyState, Icon, PrimaryButton, SectionTitle, shortID } from './ui';

export function GraphPage({ onOpenEvidence, onError }: {
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [relations, setRelations] = useState<EnterpriseGraphRelation[]>([]);
  const [mode, setMode] = useState<'relations' | 'graph'>('relations');
  const [loading, setLoading] = useState(false);

  const runSearch = async () => {
    if (!query.trim()) return;
    setLoading(true);
    try {
      const data = await fetchEnterpriseGraph(query.trim());
      setRelations(data.relations || []);
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

  return <div class="space-y-6">
    <section class="rounded-[28px] border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-7">
      <SectionTitle eyebrow="Bounded traversal" title="从实体出发查看有证据的企业关系" description="Graph 不追求把所有事实都图谱化，只保留对关联、多跳与时间查询有价值、且拥有 ACTIVE Evidence Support 的关系。" />
      <div class="mt-6 flex flex-col gap-3 lg:flex-row">
        <div class="relative flex-1"><Icon name="graph" size={17} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void runSearch(); }} placeholder="输入公司、人员、合同、项目或机构名称" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-3 pl-10 pr-3 text-sm outline-none focus:border-amber-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <PrimaryButton onClick={() => void runSearch()} disabled={loading || !query.trim()} icon="graph">{loading ? '查询中…' : '查询 1–2 hop'}</PrimaryButton>
      </div>
      <div class="mt-4 flex items-center justify-between border-t border-slate-100 pt-4 dark:border-slate-800">
        <div class="flex items-center gap-2 text-xs text-slate-400"><Icon name="shield" size={14}/><span>仅返回拥有 ACTIVE Evidence Support 的 Relation Fact</span></div>
        <div class="flex rounded-xl bg-slate-100 p-1 dark:bg-slate-800"><button onClick={() => setMode('relations')} class={`rounded-lg px-3 py-1.5 text-xs font-semibold ${mode === 'relations' ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-700 dark:text-white' : 'text-slate-400'}`}>关系浏览</button><button onClick={() => setMode('graph')} class={`rounded-lg px-3 py-1.5 text-xs font-semibold ${mode === 'graph' ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-700 dark:text-white' : 'text-slate-400'}`}>Graph View</button></div>
      </div>
    </section>

    {relations.length === 0 && !loading && <EmptyState icon="graph" title="输入实体关键词开始浏览关系" description="Neo4j 从企业实体全文索引 Seed，最多展开 2 hop、10 个关系，并在返回前检查 Evidence Support。" />}

    {relations.length > 0 && mode === 'relations' && <section class="space-y-4">
      <SectionTitle eyebrow="Relations" title={`${relations.length} 条 Evidence-backed Relation`} description="点击 Evidence 可以直接回到原始 Document Version、page/block/bbox 与 Raw Content。" />
      <div class="grid gap-4 lg:grid-cols-2">{relations.map(rel => <article key={rel.relation_id} class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div class="flex items-start gap-4"><EntityAvatar name={rel.source_name}/><div class="min-w-0 flex-1 pt-1 text-center"><div class="truncate text-xs font-semibold uppercase tracking-[0.1em] text-amber-600 dark:text-amber-400">{humanRelation(rel.relation_type)}</div><div class="my-2 flex items-center gap-2 text-slate-300 dark:text-slate-600"><div class="h-px flex-1 bg-current"/><Icon name="arrow" size={15}/></div><div class="text-[10px] text-slate-400">score {Number(rel.score || 0).toFixed(4)}</div></div><EntityAvatar name={rel.target_name}/></div>
        <div class="mt-5 flex items-center justify-between border-t border-slate-100 pt-4 text-xs dark:border-slate-800"><span class="text-slate-400">Relation {shortID(rel.relation_id)}</span><span class="text-slate-400">{rel.evidence_ids?.length || 0} Evidence</span></div>
        <div class="mt-3 flex flex-wrap gap-2">{(rel.evidence_ids || []).map(id => <button key={id} onClick={() => void openEvidence(id)} class="inline-flex items-center gap-1.5 rounded-lg bg-emerald-50 px-2.5 py-1.5 text-xs font-medium text-emerald-700 hover:bg-emerald-100 dark:bg-emerald-950/40 dark:text-emerald-300 dark:hover:bg-emerald-950/70"><Icon name="evidence" size={13}/>Evidence {shortID(id)}</button>)}</div>
      </article>)}</div>
    </section>}

    {relations.length > 0 && mode === 'graph' && <section class="rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <SectionTitle eyebrow="Graph view" title="关系拓扑" description="轻量可视化用于理解当前有界查询的实体关系；证据审计仍以 Relation / Evidence 为准。" />
      <div class="mt-5 overflow-hidden rounded-2xl border border-slate-100 bg-slate-50 dark:border-slate-800 dark:bg-slate-950/60"><GraphCanvas relations={relations}/></div>
      <div class="mt-4 flex flex-wrap gap-2">{relations.map(rel => <div key={rel.relation_id} class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-[11px] text-slate-500 dark:border-slate-700 dark:text-slate-400">{rel.source_name} → {humanRelation(rel.relation_type)} → {rel.target_name}</div>)}</div>
    </section>}
  </div>;
}

function EntityAvatar({ name }: { name: string }) {
  return <div class="min-w-0 max-w-[36%] text-center"><div class="mx-auto flex h-11 w-11 items-center justify-center rounded-2xl bg-slate-950 text-sm font-semibold text-white dark:bg-white dark:text-slate-950">{(name || '?').slice(0, 1).toUpperCase()}</div><div class="mt-2 break-words text-sm font-semibold text-slate-700 dark:text-slate-200">{name || 'Unknown'}</div></div>;
}

function GraphCanvas({ relations }: { relations: EnterpriseGraphRelation[] }) {
  const { nodes, edges } = useMemo(() => buildLayout(relations), [relations]);
  const height = Math.max(360, Math.ceil(nodes.length / 4) * 130 + 80);
  return <svg viewBox={`0 0 1000 ${height}`} class="min-h-[360px] w-full" role="img" aria-label="企业关系图">
    <defs><marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L0,6 L7,3 z" fill="currentColor"/></marker></defs>
    <g class="text-slate-300 dark:text-slate-700">{edges.map(edge => <g key={edge.id}><line x1={edge.x1} y1={edge.y1} x2={edge.x2} y2={edge.y2} stroke="currentColor" stroke-width="2" marker-end="url(#graph-arrow)"/><text x={(edge.x1 + edge.x2) / 2} y={(edge.y1 + edge.y2) / 2 - 7} text-anchor="middle" class="fill-slate-400 text-[11px] font-medium">{humanRelation(edge.label)}</text></g>)}</g>
    {nodes.map(node => <g key={node.id} transform={`translate(${node.x},${node.y})`}><rect x="-78" y="-28" width="156" height="56" rx="16" class="fill-white stroke-slate-200 dark:fill-slate-900 dark:stroke-slate-700" stroke-width="1.5"/><circle cx="-55" cy="0" r="11" class="fill-slate-900 dark:fill-white"/><text x="-35" y="5" class="fill-slate-700 text-[12px] font-semibold dark:fill-slate-200">{truncate(node.label, 18)}</text></g>)}
  </svg>;
}

function buildLayout(relations: EnterpriseGraphRelation[]) {
  const names = Array.from(new Set(relations.flatMap(rel => [rel.source_name, rel.target_name])));
  const cols = Math.min(4, Math.max(2, Math.ceil(Math.sqrt(names.length))));
  const nodes = names.map((label, index) => ({ id: label, label, x: 130 + (index % cols) * (740 / Math.max(cols - 1, 1)), y: 80 + Math.floor(index / cols) * 130 }));
  const lookup = new Map(nodes.map(node => [node.id, node]));
  const edges = relations.map(rel => {
    const source = lookup.get(rel.source_name)!;
    const target = lookup.get(rel.target_name)!;
    return { id: rel.relation_id, label: rel.relation_type, x1: source.x, y1: source.y, x2: target.x, y2: target.y };
  });
  return { nodes, edges };
}

function humanRelation(value: string) {
  return (value || 'RELATED_TO').replaceAll('_', ' ');
}

function truncate(value: string, max: number) {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}
