import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, fetchEnterpriseGraph, type EnterpriseEvidence, type EnterpriseGraphRelation } from '../lib/api';
import { EmptyState, Icon, PrimaryButton, SecondaryButton, SectionTitle } from './ui';

export function GraphPage({ onOpenEvidence, onError }: {
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [relations, setRelations] = useState<EnterpriseGraphRelation[]>([]);
  const [mode, setMode] = useState<'relations' | 'graph'>('graph');
  const [loading, setLoading] = useState(false);
  const [depth, setDepth] = useState<1 | 2>(2);
  const [relationType, setRelationType] = useState('');
  const [validAt, setValidAt] = useState('');
  const [selectedRelationId, setSelectedRelationId] = useState('');

  const relationTypes = useMemo(
    () => Array.from(new Set(relations.map(item => item.relation_type).filter(Boolean))).sort(),
    [relations],
  );
  const selectedRelation = useMemo(
    () => relations.find(item => item.relation_id === selectedRelationId) || null,
    [relations, selectedRelationId],
  );

  const runSearch = async (value = query) => {
    const next = value.trim();
    if (!next) return;
    setQuery(next);
    setLoading(true);
    try {
      const data = await fetchEnterpriseGraph(next, {
        depth,
        limit: 50,
        relation_type: relationType || undefined,
        valid_at: validAt || undefined,
      });
      setRelations(data.relations || []);
      setSelectedRelationId(data.relations?.[0]?.relation_id || '');
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setLoading(false);
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

  const expandEntity = (name: string) => void runSearch(name);

  return <div class="space-y-6">
    <section class="rounded-[28px] border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-7">
      <SectionTitle title="关系查询" description="从一个公司、人员、合同、项目或机构出发，按需展开 1～2 跳关系。" />
      <div class="mt-6 flex flex-col gap-3 lg:flex-row">
        <div class="relative flex-1"><Icon name="graph" size={17} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void runSearch(); }} placeholder="输入公司、人员、合同、项目或机构名称" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-3 pl-10 pr-3 text-sm outline-none focus:border-amber-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <PrimaryButton onClick={() => void runSearch()} disabled={loading || !query.trim()} icon="graph">{loading ? '查询中…' : '查询'}</PrimaryButton>
      </div>
      <div class="mt-4 grid gap-3 border-t border-slate-100 pt-4 dark:border-slate-800 sm:grid-cols-3 lg:grid-cols-[180px_minmax(180px,1fr)_220px_auto]">
        <label class="text-xs text-slate-500 dark:text-slate-400"><span class="mb-1.5 block font-medium">展开层级</span><select value={depth} onChange={event => setDepth(Number((event.target as HTMLSelectElement).value) as 1 | 2)} class="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-950"><option value="1">1 跳</option><option value="2">2 跳</option></select></label>
        <label class="text-xs text-slate-500 dark:text-slate-400"><span class="mb-1.5 block font-medium">关系类型</span><select value={relationType} onChange={event => setRelationType((event.target as HTMLSelectElement).value)} class="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-950"><option value="">全部关系</option>{relationTypes.map(item => <option key={item} value={item}>{humanRelation(item)}</option>)}</select></label>
        <label class="text-xs text-slate-500 dark:text-slate-400"><span class="mb-1.5 block font-medium">关系有效时点</span><input type="date" value={validAt} onInput={event => setValidAt((event.target as HTMLInputElement).value)} class="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-950"/></label>
        <div class="flex items-end"><SecondaryButton onClick={() => void runSearch()} disabled={!query.trim() || loading} icon="refresh">应用筛选</SecondaryButton></div>
      </div>
      <div class="mt-4 flex justify-end">
        <div class="flex rounded-xl bg-blue-50 p-1 dark:bg-blue-950/30"><button onClick={() => setMode('relations')} class={`rounded-lg px-3 py-1.5 text-xs font-semibold transition ${mode === 'relations' ? 'bg-blue-600 text-white shadow-sm dark:bg-blue-500' : 'text-blue-600 hover:bg-blue-100 dark:text-blue-300 dark:hover:bg-blue-950/60'}`}>关系浏览</button><button onClick={() => setMode('graph')} class={`rounded-lg px-3 py-1.5 text-xs font-semibold transition ${mode === 'graph' ? 'bg-blue-600 text-white shadow-sm dark:bg-blue-500' : 'text-blue-600 hover:bg-blue-100 dark:text-blue-300 dark:hover:bg-blue-950/60'}`}>图谱视图</button></div>
      </div>
    </section>

    {relations.length === 0 && !loading && <EmptyState icon="graph" title="输入名称开始查询" description="图谱只按当前查询加载局部关系，不会一次加载全图。" />}

    {relations.length > 0 && mode === 'relations' && <section class="space-y-4">
      <SectionTitle title={`${relations.length} 条关系`} />
      <div class="grid gap-4 lg:grid-cols-2">{relations.map(rel => <article key={rel.relation_id} onClick={() => setSelectedRelationId(rel.relation_id)} class={`cursor-pointer rounded-2xl border bg-white p-5 shadow-sm transition dark:bg-slate-900 ${selectedRelationId === rel.relation_id ? 'border-blue-300 ring-2 ring-blue-100 dark:border-blue-700 dark:ring-blue-950/40' : 'border-slate-200/80 hover:border-blue-200 dark:border-slate-800'}`}>
        <div class="flex items-start gap-4"><EntityAvatar name={rel.source_name} type={rel.source_type}/><div class="min-w-0 flex-1 pt-1 text-center"><div class="truncate text-xs font-semibold tracking-[0.1em] text-amber-600 dark:text-amber-400">{humanRelation(rel.relation_type)}</div><div class="my-2 flex items-center gap-2 text-slate-300 dark:text-slate-600"><div class="h-px flex-1 bg-current"/><Icon name="arrow" size={15}/></div><div class="text-[10px] text-slate-400">{relationPeriod(rel)}</div></div><EntityAvatar name={rel.target_name} type={rel.target_type}/></div>
        {rel.conflict_status && rel.conflict_status !== 'NONE' && <div class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-xs text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">关系存在 {rel.conflict_status.toLowerCase()} 冲突标记</div>}
        {(rel.evidence_ids || []).length > 0 && <div class="mt-5 border-t border-slate-100 pt-4 dark:border-slate-800"><div class="mb-2 text-xs text-slate-400">参考资料 {rel.evidence_ids.length} 条</div><div class="flex flex-wrap gap-2">{rel.evidence_ids.map(id => <button key={id} onClick={event => { event.stopPropagation(); void openEvidence(id); }} class="rounded-lg border border-blue-100 bg-blue-50/70 px-2.5 py-1.5 text-xs font-medium text-blue-700 transition hover:border-blue-300 hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60">查看原文</button>)}</div></div>}
      </article>)}</div>
    </section>}

    {relations.length > 0 && mode === 'graph' && <section class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
      <div class="rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-6">
        <SectionTitle title="关系拓扑" description="滚轮缩放，拖动画布平移，拖动节点调整位置；点击节点可继续展开。" />
        <div class="mt-5 overflow-hidden rounded-2xl border border-slate-100 bg-slate-50 dark:border-slate-800 dark:bg-slate-950/60"><GraphCanvas relations={relations} selectedRelationId={selectedRelationId} onSelectRelation={setSelectedRelationId} onExpandEntity={expandEntity}/></div>
      </div>
      <aside class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {selectedRelation ? <RelationDetail relation={selectedRelation} onOpenEvidence={openEvidence} onExpandEntity={expandEntity}/> : <EmptyState icon="graph" title="选择一条关系" description="查看关系类型、有效时间和原文依据。"/>}
      </aside>
    </section>}
  </div>;
}

function RelationDetail({ relation, onOpenEvidence, onExpandEntity }: { relation: EnterpriseGraphRelation; onOpenEvidence: (id: string) => Promise<void>; onExpandEntity: (name: string) => void }) {
  return <div class="space-y-5">
    <div><div class="text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">关系详情</div><div class="mt-2 text-lg font-semibold text-slate-800 dark:text-slate-100">{humanRelation(relation.relation_type)}</div><div class="mt-1 text-xs text-slate-400">{relationPeriod(relation)}</div></div>
    <div class="grid gap-3"><EntityDetail label="起点" name={relation.source_name} type={relation.source_type} onExpand={onExpandEntity}/><EntityDetail label="终点" name={relation.target_name} type={relation.target_type} onExpand={onExpandEntity}/></div>
    <div class="grid grid-cols-2 gap-2 text-xs"><Detail label="置信度" value={Number.isFinite(relation.score) ? relation.score.toFixed(3) : '—'}/><Detail label="冲突状态" value={relation.conflict_status || 'NONE'}/></div>
    <div><div class="mb-2 text-xs font-semibold text-slate-500 dark:text-slate-300">原文依据</div>{relation.evidence_ids.length ? <div class="space-y-2">{relation.evidence_ids.map((id, index) => <button key={id} onClick={() => void onOpenEvidence(id)} class="flex w-full items-center justify-between rounded-xl border border-slate-200 px-3 py-2.5 text-left text-xs transition hover:border-blue-300 hover:text-blue-700 dark:border-slate-700"><span>Evidence {index + 1}</span><Icon name="arrow" size={14}/></button>)}</div> : <div class="text-xs text-slate-400">暂无可用 Evidence。</div>}</div>
  </div>;
}

function EntityDetail({ label, name, type, onExpand }: { label: string; name: string; type: string; onExpand: (name: string) => void }) {
  return <button onClick={() => onExpand(name)} class="rounded-xl border border-slate-200 p-3 text-left transition hover:border-blue-300 dark:border-slate-700"><div class="text-[10px] uppercase tracking-wide text-slate-400">{label} · {humanEntityType(type)}</div><div class="mt-1 font-semibold text-slate-700 dark:text-slate-200">{name}</div><div class="mt-1 text-[11px] text-blue-600 dark:text-blue-400">从此节点继续展开</div></button>;
}

function Detail({ label, value }: { label: string; value: string }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 break-all font-medium text-slate-600 dark:text-slate-300">{value}</div></div>; }

function EntityAvatar({ name, type }: { name: string; type: string }) {
  return <div class="min-w-0 max-w-[36%] text-center"><div class={`mx-auto flex h-11 w-11 items-center justify-center rounded-2xl text-sm font-semibold text-white ${nodeClass(type)}`}>{(name || '?').slice(0, 1).toUpperCase()}</div><div class="mt-2 break-words text-sm font-semibold text-slate-700 dark:text-slate-200">{name || '未知实体'}</div><div class="mt-1 text-[10px] text-slate-400">{humanEntityType(type)}</div></div>;
}

type GraphNode = { id: string; label: string; type: string; x: number; y: number };
type GraphEdge = { id: string; label: string; sourceId: string; targetId: string; relation: EnterpriseGraphRelation };

function GraphCanvas({ relations, selectedRelationId, onSelectRelation, onExpandEntity }: { relations: EnterpriseGraphRelation[]; selectedRelationId: string; onSelectRelation: (id: string) => void; onExpandEntity: (name: string) => void }) {
  const svgRef = useRef<SVGSVGElement>(null);
  const initial = useMemo(() => buildLayout(relations), [relations]);
  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>({});
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const dragRef = useRef<{ kind: 'canvas' | 'node'; id?: string; startX: number; startY: number; originX: number; originY: number } | null>(null);

  useEffect(() => {
    setPositions(Object.fromEntries(initial.nodes.map(node => [node.id, { x: node.x, y: node.y }])));
    setZoom(1);
    setPan({ x: 0, y: 0 });
  }, [initial]);

  const pointScale = () => {
    const width = svgRef.current?.getBoundingClientRect().width || 1000;
    return 1000 / width / zoom;
  };
  const pointerMove = (event: PointerEvent) => {
    const drag = dragRef.current;
    if (!drag) return;
    const factor = pointScale();
    const dx = (event.clientX - drag.startX) * factor;
    const dy = (event.clientY - drag.startY) * factor;
    if (drag.kind === 'canvas') setPan({ x: drag.originX + dx, y: drag.originY + dy });
    else if (drag.id) setPositions(current => ({ ...current, [drag.id!]: { x: drag.originX + dx, y: drag.originY + dy } }));
  };
  const pointerUp = () => { dragRef.current = null; };
  const wheel = (event: WheelEvent) => {
    event.preventDefault();
    setZoom(current => clamp(current * (event.deltaY > 0 ? 0.9 : 1.1), 0.5, 2.4));
  };

  const nodes = initial.nodes.map(node => ({ ...node, ...(positions[node.id] || { x: node.x, y: node.y }) }));
  const lookup = new Map(nodes.map(node => [node.id, node]));

  return <div class="relative h-[560px] min-h-[420px] touch-none">
    <div class="absolute right-3 top-3 z-10 flex gap-1 rounded-xl border border-slate-200 bg-white/90 p-1 shadow-sm backdrop-blur dark:border-slate-700 dark:bg-slate-900/90"><button aria-label="缩小" onClick={() => setZoom(value => clamp(value - .15, .5, 2.4))} class="rounded-lg px-2.5 py-1.5 text-sm hover:bg-slate-100 dark:hover:bg-slate-800">−</button><button aria-label="重置视图" onClick={() => { setZoom(1); setPan({ x: 0, y: 0 }); setPositions(Object.fromEntries(initial.nodes.map(node => [node.id, { x: node.x, y: node.y }]))); }} class="rounded-lg px-2.5 py-1.5 text-xs hover:bg-slate-100 dark:hover:bg-slate-800">重置</button><button aria-label="放大" onClick={() => setZoom(value => clamp(value + .15, .5, 2.4))} class="rounded-lg px-2.5 py-1.5 text-sm hover:bg-slate-100 dark:hover:bg-slate-800">＋</button></div>
    <svg ref={svgRef} viewBox="0 0 1000 560" class="h-full w-full cursor-grab active:cursor-grabbing" role="img" aria-label="企业关系图" onWheel={wheel as never} onPointerDown={event => { const e = event as PointerEvent; dragRef.current = { kind: 'canvas', startX: e.clientX, startY: e.clientY, originX: pan.x, originY: pan.y }; }} onPointerMove={pointerMove as never} onPointerUp={pointerUp} onPointerLeave={pointerUp}>
      <defs><marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L0,6 L7,3 z" fill="currentColor"/></marker></defs>
      <g transform={`translate(${pan.x} ${pan.y}) scale(${zoom})`}>
        {initial.edges.map(edge => { const source = lookup.get(edge.sourceId); const target = lookup.get(edge.targetId); if (!source || !target) return null; const selected = edge.id === selectedRelationId; return <g key={edge.id} onClick={event => { event.stopPropagation(); onSelectRelation(edge.id); }} class="cursor-pointer"><line x1={source.x} y1={source.y} x2={target.x} y2={target.y} stroke="currentColor" stroke-width={selected ? 4 : 2} marker-end="url(#graph-arrow)" class={selected ? 'text-blue-500' : 'text-slate-300 dark:text-slate-700'}/><text x={(source.x + target.x) / 2} y={(source.y + target.y) / 2 - 8} text-anchor="middle" class={`text-[11px] font-medium ${selected ? 'fill-blue-600 dark:fill-blue-400' : 'fill-slate-400'}`}>{truncate(humanRelation(edge.label), 20)}</text></g>; })}
        {nodes.map(node => { const selected = initial.edges.some(edge => edge.id === selectedRelationId && (edge.sourceId === node.id || edge.targetId === node.id)); return <g key={node.id} transform={`translate(${node.x},${node.y})`} class="cursor-move" onDblClick={event => { event.stopPropagation(); onExpandEntity(node.label); }} onPointerDown={event => { const e = event as PointerEvent; e.stopPropagation(); dragRef.current = { kind: 'node', id: node.id, startX: e.clientX, startY: e.clientY, originX: node.x, originY: node.y }; }}><rect x="-84" y="-31" width="168" height="62" rx="17" class={selected ? 'fill-blue-50 stroke-blue-400 dark:fill-blue-950/40 dark:stroke-blue-500' : 'fill-white stroke-slate-200 dark:fill-slate-900 dark:stroke-slate-700'} stroke-width={selected ? 2.5 : 1.5}/><circle cx="-59" cy="0" r="12" class={nodeSvgClass(node.type)}/><text x="-39" y="-2" class="fill-slate-700 text-[12px] font-semibold dark:fill-slate-200">{truncate(node.label, 18)}</text><text x="-39" y="14" class="fill-slate-400 text-[9px]">{truncate(humanEntityType(node.type), 20)}</text></g>; })}
      </g>
    </svg>
  </div>;
}

function buildLayout(relations: EnterpriseGraphRelation[]) {
  const nodesById = new Map<string, { label: string; type: string }>();
  for (const rel of relations) {
    nodesById.set(rel.source_entity_id, { label: rel.source_name, type: rel.source_type });
    nodesById.set(rel.target_entity_id, { label: rel.target_name, type: rel.target_type });
  }
  const entries = Array.from(nodesById.entries());
  const cols = Math.min(4, Math.max(2, Math.ceil(Math.sqrt(entries.length))));
  const nodes: GraphNode[] = entries.map(([id, value], index) => ({ id, label: value.label, type: value.type, x: 130 + (index % cols) * (740 / Math.max(cols - 1, 1)), y: 90 + Math.floor(index / cols) * 130 }));
  const edges: GraphEdge[] = relations.map(rel => ({ id: rel.relation_id, label: rel.relation_type, sourceId: rel.source_entity_id, targetId: rel.target_entity_id, relation: rel }));
  return { nodes, edges };
}

function relationPeriod(rel: EnterpriseGraphRelation) {
  if (!rel.valid_from && !rel.valid_to) return '有效时间未限定';
  return `${rel.valid_from ? formatTime(rel.valid_from) : '起始未知'} → ${rel.valid_to ? formatTime(rel.valid_to) : '持续有效'}`;
}
function formatTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString(); }
function humanRelation(value: string) { return (value || 'RELATED_TO').replaceAll('_', ' '); }
function humanEntityType(value: string) { return (value || 'other').replaceAll('_', ' '); }
function truncate(value: string, max: number) { return value.length > max ? `${value.slice(0, max - 1)}…` : value; }
function clamp(value: number, min: number, max: number) { return Math.max(min, Math.min(max, value)); }
function nodeClass(type: string) { const value = (type || '').toLowerCase(); if (value.includes('person')) return 'bg-amber-500'; if (value.includes('contract')) return 'bg-violet-500'; if (value.includes('event') || value.includes('risk')) return 'bg-rose-500'; if (value.includes('org') || value.includes('company')) return 'bg-blue-600'; return 'bg-slate-600'; }
function nodeSvgClass(type: string) { const value = (type || '').toLowerCase(); if (value.includes('person')) return 'fill-amber-500'; if (value.includes('contract')) return 'fill-violet-500'; if (value.includes('event') || value.includes('risk')) return 'fill-rose-500'; if (value.includes('org') || value.includes('company')) return 'fill-blue-600'; return 'fill-slate-600'; }
