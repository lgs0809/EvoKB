import { useEffect, useMemo, useState } from 'preact/hooks';
import {
  fetchEnterpriseDocumentDetail,
  fetchEnterpriseDocuments,
  fetchEnterpriseEvidence,
  fetchEnterpriseFacets,
  recommendEnterpriseKnowledgeGeneration,
  startEnterpriseKnowledgeGeneration,
  updateEnterpriseDocumentMetadata,
  type EnterpriseDocumentDetail,
  type EnterpriseDocumentVersion,
  type EnterpriseEvidence,
  type EnterpriseFacets,
} from '../lib/api';
import { EmptyState, Icon, LoadingBlock, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge } from './ui';
import type { EnterpriseSection } from './EnterpriseShell';

const PAGE_SIZE = 30;
type DetailTab = 'info' | 'content' | 'versions' | 'knowledge' | 'evidence';
type DocumentMetadataForm = { logicalName: string; sourceDepartment: string; documentType: string; materialCategory: string; documentDate: string; tags: string };

export function DocumentsPage({ onNavigate, onOpenEvidence, onError, canUpload, isAdmin, onChanged }: { onNavigate: (section: EnterpriseSection) => void; onOpenEvidence: (evidence: EnterpriseEvidence) => void; onError: (message: string) => void; canUpload: boolean; isAdmin: boolean; onChanged: () => void }) {
  const [items, setItems] = useState<EnterpriseDocumentVersion[]>([]);
  const [selected, setSelected] = useState<EnterpriseDocumentVersion | null>(null);
  const [versions, setVersions] = useState<EnterpriseDocumentVersion[]>([]);
  const [detail, setDetail] = useState<EnterpriseDocumentDetail | null>(null);
  const [tab, setTab] = useState<DetailTab>('info');
  const [detailBusy, setDetailBusy] = useState(false);
  const [facets, setFacets] = useState<EnterpriseFacets>({ companies: [], source_departments: [], document_types: [], material_categories: [] });
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState('');
  const [department, setDepartment] = useState('ALL');
  const [documentType, setDocumentType] = useState('ALL');
  const [category, setCategory] = useState('ALL');
  const [lifecycle, setLifecycle] = useState('ALL');
  const [loading, setLoading] = useState(false);
  const [recommending, setRecommending] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [editingMetadata, setEditingMetadata] = useState(false);
  const [metadataBusy, setMetadataBusy] = useState(false);

  const filters = useMemo(() => ({ q: query || undefined, source_department: department, document_type: documentType, material_category: category, lifecycle }), [query, department, documentType, category, lifecycle]);
  const load = async (nextPage = page) => {
    setLoading(true);
    try {
      const response = await fetchEnterpriseDocuments({ page: nextPage, page_size: PAGE_SIZE, ...filters });
      setItems(response.documents || []);
      setTotal(response.total || 0);
      setPage(nextPage);
      setSelected(current => current ? (response.documents || []).find(item => item.version_id === current.version_id) || current : null);
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchEnterpriseFacets().then(setFacets).catch(() => undefined); }, []);
  useEffect(() => { void load(1); }, [query, department, documentType, category, lifecycle]);

  const selectVersion = async (item: EnterpriseDocumentVersion) => {
    setSelected(item);
    setTab('info');
    setDetailBusy(true);
    try {
      const [history, nextDetail] = await Promise.all([
        fetchEnterpriseDocuments({ page: 1, page_size: 50, document_id: item.document_id }),
        fetchEnterpriseDocumentDetail(item.version_id, 160),
      ]);
      setVersions(history.documents || []);
      setDetail(nextDetail);
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setDetailBusy(false); }
  };

  const openEvidence = async (evidenceId: string) => {
    if (!evidenceId) return;
    try { onOpenEvidence(await fetchEnterpriseEvidence(evidenceId)); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
  };

  const recommend = async () => {
    if (!selected) return;
    setRecommending(true);
    try { await recommendEnterpriseKnowledgeGeneration(selected.version_id); await selectVersion(selected); await load(page); onChanged(); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setRecommending(false); }
  };

  const generate = async () => {
    if (!selected) return;
    setGenerating(true);
    try {
      await startEnterpriseKnowledgeGeneration(selected.version_id);
      await selectVersion(selected);
      onChanged();
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setGenerating(false); }
  };

  const saveMetadata = async (input: DocumentMetadataForm) => {
    if (!selected) return;
    setMetadataBusy(true);
    try {
      const updated = await updateEnterpriseDocumentMetadata(selected.version_id, {
        logical_name: input.logicalName,
        source_department: input.sourceDepartment,
        material_category: input.materialCategory,
        document_type: input.documentType,
        document_date: input.documentDate,
        manual_tags: input.tags.split(',').map(value => value.trim()).filter(Boolean),
      });
      setSelected(updated);
      setItems(current => current.map(item => item.version_id === updated.version_id ? updated : item));
      setEditingMetadata(false);
      fetchEnterpriseFacets().then(setFacets).catch(() => undefined);
      onChanged();
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setMetadataBusy(false); }
  };

  const clearFilters = () => { setDepartment('ALL'); setDocumentType('ALL'); setCategory('ALL'); setLifecycle('ALL'); };
  const hasFilter = [department, documentType, category, lifecycle].some(value => value !== 'ALL');
  const maxPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return <div class="space-y-5">
    <SectionTitle title="资料" description="管理企业资料、版本、解析内容、知识生成状态与原文证据。" action={<div class="flex gap-2">{isAdmin && <SecondaryButton onClick={() => onNavigate('tasks')} icon="sparkles">处理任务</SecondaryButton>}{canUpload && <PrimaryButton onClick={() => onNavigate('ingestion')} icon="upload">添加资料</PrimaryButton>}</div>}/>

    <section class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-5">
      <div class="grid gap-3 lg:grid-cols-[minmax(260px,1.3fr)_repeat(4,minmax(120px,.7fr))_auto]">
        <SearchBox value={query} onChange={setQuery} placeholder="搜索资料名称或文件名"/>
        <LocalFilter label="部门" value={department} options={facets.source_departments} onChange={setDepartment}/>
        <LocalFilter label="资料类型" value={documentType} options={facets.document_types} onChange={setDocumentType}/>
        <LocalFilter label="资料分类" value={category} options={facets.material_categories} onChange={setCategory}/>
        <LocalFilter label="状态" value={lifecycle} options={['ACTIVE','PENDING','FAILED']} onChange={setLifecycle}/>
        <div class="flex items-end"><button onClick={clearFilters} disabled={!hasFilter} class="rounded-xl border border-slate-200 px-3 py-2 text-xs font-medium text-slate-500 hover:border-blue-300 hover:text-blue-700 disabled:opacity-40 dark:border-slate-700">清除</button></div>
      </div>
    </section>

    <div class={`grid gap-5 ${selected ? 'xl:grid-cols-[minmax(0,1fr)_minmax(430px,.78fr)]' : ''}`}>
      <div class="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {loading ? <div class="p-5"><LoadingBlock rows={7}/></div> : items.length === 0 ? <div class="p-5"><EmptyState icon="documents" title="暂无资料" description={query || hasFilter ? '当前条件下没有匹配的资料。' : '添加资料后会显示在这里。'} /></div> : <div class="overflow-x-auto"><table class="w-full min-w-[840px] text-left text-sm"><thead class="border-b border-slate-200 bg-slate-50/80 text-[11px] uppercase tracking-[0.08em] text-slate-400 dark:border-slate-800 dark:bg-slate-950/50"><tr><th class="px-4 py-3 font-semibold">资料</th><th class="px-4 py-3 font-semibold">部门</th><th class="px-4 py-3 font-semibold">类型</th><th class="px-4 py-3 font-semibold">版本</th><th class="px-4 py-3 font-semibold">状态</th><th class="px-4 py-3 font-semibold">知识</th></tr></thead><tbody class="divide-y divide-slate-100 dark:divide-slate-800">{items.map(item => <tr key={item.version_id} onClick={() => void selectVersion(item)} class={`cursor-pointer transition hover:bg-blue-50/60 dark:hover:bg-blue-950/20 ${selected?.version_id === item.version_id ? 'bg-blue-50 dark:bg-blue-950/30' : ''}`}><td class="px-4 py-4"><div class="font-semibold text-slate-700 dark:text-slate-200">{item.logical_name || item.original_filename}</div><div class="mt-1 max-w-[320px] truncate text-xs text-slate-400">{item.original_filename}</div></td><td class="px-4 py-4 text-slate-600 dark:text-slate-300">{item.source_department || '—'}</td><td class="px-4 py-4 text-xs text-slate-500 dark:text-slate-400"><div>{item.document_type || '未分类'}</div><div class="mt-1">{item.material_category || '—'}</div></td><td class="px-4 py-4"><div class="font-medium text-slate-600 dark:text-slate-300">v{item.version_no}</div><div class="mt-1 text-xs text-slate-400">{item.document_date || item.parsed_at?.slice(0,10) || '—'}</div></td><td class="px-4 py-4"><StatusBadge value={item.lifecycle}/></td><td class="px-4 py-4"><div class="text-xs font-medium text-slate-600 dark:text-slate-300">Summary {item.grounded_summaries}/{item.summary_count}</div><div class="mt-1 text-[11px] text-slate-400">Coverage {item.covered_summaries}/{item.summary_count} · 命中 {item.effective_hits}</div></td></tr>)}</tbody></table></div>}
        <div class="flex flex-col gap-3 border-t border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800 sm:flex-row sm:items-center sm:justify-between"><span>共 {total} 个版本</span><div class="flex items-center gap-2"><SecondaryButton onClick={() => void load(Math.max(1,page-1))} disabled={page<=1}>上一页</SecondaryButton><span>{page} / {maxPage}</span><SecondaryButton onClick={() => void load(Math.min(maxPage,page+1))} disabled={page>=maxPage}>下一页</SecondaryButton></div></div>
      </div>

      {selected && <aside class="self-start rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 xl:sticky xl:top-6">
        <div class="border-b border-slate-100 p-5 dark:border-slate-800"><div class="flex items-start justify-between gap-3"><div class="min-w-0"><div class="text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-400">资料详情</div><h3 class="mt-1 break-words text-lg font-semibold text-slate-800 dark:text-slate-100">{selected.logical_name || selected.original_filename}</h3></div><div class="flex shrink-0 items-center gap-1">{canUpload && <button aria-label="编辑资料信息" title="编辑资料信息" onClick={() => setEditingMetadata(true)} class="rounded-lg p-2 text-blue-600 hover:bg-blue-50 dark:text-blue-300 dark:hover:bg-blue-950/30"><Icon name="edit" size={16}/></button>}<button aria-label="关闭详情" onClick={() => { setSelected(null); setDetail(null); }} class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><Icon name="close" size={16}/></button></div></div>
          <div class="mt-4 flex gap-1 overflow-x-auto rounded-xl bg-slate-50 p-1 dark:bg-slate-950">{([['info','资料信息'],['content','内容'],['versions','版本'],['knowledge','知识生成'],['evidence','证据']] as const).map(([key,label]) => <button key={key} onClick={() => setTab(key)} class={`shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold transition ${tab === key ? 'bg-white text-blue-700 shadow-sm dark:bg-slate-800 dark:text-blue-300' : 'text-slate-500 hover:text-slate-700 dark:text-slate-400'}`}>{label}</button>)}</div>
        </div>
        <div class="max-h-[72vh] overflow-y-auto p-5">{detailBusy && <div class="py-16 text-center text-sm text-slate-400"><Icon name="refresh" size={20} class="mx-auto mb-2 animate-spin"/>正在读取资料详情…</div>}{!detailBusy && tab === 'info' && <InfoTab selected={selected}/>} {!detailBusy && tab === 'content' && <ContentTab detail={detail}/>} {!detailBusy && tab === 'versions' && <VersionsTab versions={versions} selected={selected} onSelect={selectVersion}/>} {!detailBusy && tab === 'knowledge' && <KnowledgeTab selected={selected} detail={detail} canRecommend={isAdmin} recommending={recommending} generating={generating} onRecommend={recommend} onGenerate={generate} onNavigate={onNavigate}/>} {!detailBusy && tab === 'evidence' && <EvidenceTab detail={detail} onOpenEvidence={openEvidence}/>}</div>
      </aside>}
    </div>

    {editingMetadata && selected && <DocumentMetadataDialog selected={selected} busy={metadataBusy} onClose={() => { if (!metadataBusy) setEditingMetadata(false); }} onSave={saveMetadata}/>}
  </div>;
}

function InfoTab({ selected }: { selected: EnterpriseDocumentVersion }) { return <div class="grid gap-3 text-sm"><Detail label="部门" value={selected.source_department}/><Detail label="资料类型" value={selected.document_type}/><Detail label="资料分类" value={selected.material_category}/><Detail label="资料日期" value={selected.document_date}/><Detail label="标签" value={(selected.manual_tags || []).join('、')}/><Detail label="原始文件" value={selected.original_filename}/><Detail label="版本 ID" value={selected.version_id}/><Detail label="资料 ID" value={selected.document_id}/></div>; }

function DocumentMetadataDialog({ selected, busy, onClose, onSave }: { selected: EnterpriseDocumentVersion; busy: boolean; onClose: () => void; onSave: (input: DocumentMetadataForm) => Promise<void> }) {
  const [form, setForm] = useState<DocumentMetadataForm>({ logicalName: selected.logical_name || selected.original_filename, sourceDepartment: selected.source_department || '', documentType: selected.document_type || '', materialCategory: selected.material_category || '', documentDate: selected.document_date || '', tags: (selected.manual_tags || []).join(', ') });
  const set = (key: keyof DocumentMetadataForm, value: string) => setForm(current => ({ ...current, [key]: value }));
  const inputClass = 'mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none transition focus:border-blue-400 focus:bg-white dark:border-slate-700 dark:bg-slate-950 dark:focus:border-blue-500';
  return <div class="fixed inset-0 z-[90] flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm" onMouseDown={event => { if (event.currentTarget === event.target) onClose(); }}>
    <div class="w-full max-w-2xl rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-700 dark:bg-slate-900 sm:p-6">
      <div class="flex items-start gap-3"><div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300"><Icon name="edit" size={18}/></div><div><h3 class="text-lg font-semibold">编辑资料信息</h3><p class="mt-1 text-xs leading-5 text-slate-400">修改资料名称和检索元数据，不会重新解析原文件，也不会改变原始 PDF。</p></div></div>
      <div class="mt-5 grid gap-4 sm:grid-cols-2">
        <label class="text-xs font-medium text-slate-500 sm:col-span-2">资料名称<input autoFocus value={form.logicalName} onInput={event => set('logicalName', (event.target as HTMLInputElement).value)} class={inputClass}/></label>
        <label class="text-xs font-medium text-slate-500">来源部门<input value={form.sourceDepartment} onInput={event => set('sourceDepartment', (event.target as HTMLInputElement).value)} class={inputClass}/></label>
        <label class="text-xs font-medium text-slate-500">资料类型<input value={form.documentType} onInput={event => set('documentType', (event.target as HTMLInputElement).value)} class={inputClass}/></label>
        <label class="text-xs font-medium text-slate-500">材料分类<input value={form.materialCategory} onInput={event => set('materialCategory', (event.target as HTMLInputElement).value)} class={inputClass}/></label>
        <label class="text-xs font-medium text-slate-500">资料日期<input type="date" value={form.documentDate} onInput={event => set('documentDate', (event.target as HTMLInputElement).value)} class={inputClass}/></label>
        <label class="text-xs font-medium text-slate-500 sm:col-span-2">标签（逗号分隔）<input value={form.tags} onInput={event => set('tags', (event.target as HTMLInputElement).value)} class={inputClass} placeholder="例如：年报, 财务, 关联交易"/></label>
      </div>
      <div class="mt-6 flex justify-end gap-2"><button onClick={onClose} disabled={busy} class="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-500 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800">取消</button><button onClick={() => void onSave(form)} disabled={busy || !form.logicalName.trim()} class="inline-flex items-center gap-2 rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-blue-600/15 disabled:opacity-40 dark:bg-blue-500"><Icon name="check" size={15}/>{busy ? '保存中…' : '保存修改'}</button></div>
    </div>
  </div>;
}

function ContentTab({ detail }: { detail: EnterpriseDocumentDetail | null }) { if (!detail || detail.blocks.length === 0) return <EmptyState icon="documents" title="暂无解析内容" description="当前版本还没有可预览的 RawBlock。"/>; return <div class="space-y-3">{detail.blocks.map(block => <article key={block.block_id} class="rounded-xl border border-slate-200 p-3 dark:border-slate-700"><div class="flex flex-wrap items-center justify-between gap-2 text-[11px] text-slate-400"><span>Block {block.block_no} · {block.block_type}</span><span>{block.page_start ? `第 ${block.page_start}${block.page_end && block.page_end !== block.page_start ? `–${block.page_end}` : ''} 页` : '无页码'}</span></div>{block.heading_path?.length > 0 && <div class="mt-2 text-xs font-medium text-blue-700 dark:text-blue-300">{block.heading_path.join(' / ')}</div>}<div class="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-slate-600 dark:text-slate-300">{block.content}</div></article>)}</div>; }

function VersionsTab({ versions, selected, onSelect }: { versions: EnterpriseDocumentVersion[]; selected: EnterpriseDocumentVersion; onSelect: (item: EnterpriseDocumentVersion) => Promise<void> }) { return <div class="space-y-2">{versions.map(version => <button key={version.version_id} onClick={() => void onSelect(version)} class={`flex w-full items-center justify-between gap-3 rounded-xl border p-3 text-left ${version.version_id === selected.version_id ? 'border-blue-300 bg-blue-50 dark:border-blue-800 dark:bg-blue-950/30' : 'border-slate-200 dark:border-slate-700'}`}><div><div class="text-sm font-semibold">v{version.version_no}</div><div class="mt-1 text-xs text-slate-400">{version.original_filename}</div></div><StatusBadge value={version.lifecycle}/></button>)}</div>; }

function KnowledgeTab({ selected, detail, canRecommend, recommending, generating, onRecommend, onGenerate, onNavigate }: { selected: EnterpriseDocumentVersion; detail: EnterpriseDocumentDetail | null; canRecommend: boolean; recommending: boolean; generating: boolean; onRecommend: () => Promise<void>; onGenerate: () => Promise<void>; onNavigate: (section: EnterpriseSection) => void }) { const k = detail?.knowledge; const taskActive = Boolean((k?.pending_tasks ?? 0) + (k?.running_tasks ?? 0)); return <div class="space-y-5"><div class="grid grid-cols-2 gap-2 text-xs"><Metric label="Raw Blocks" value={k?.raw_blocks}/><Metric label="Evidence" value={k?.evidence}/><Metric label="Summary ACTIVE" value={`${k?.active_summaries ?? 0}/${k?.summaries ?? 0}`}/><Metric label="Grounding PASS" value={`${k?.grounded_summaries ?? 0}/${k?.summaries ?? 0}`}/><Metric label="Coverage PASS" value={`${k?.covered_summaries ?? 0}/${k?.summaries ?? 0}`}/><Metric label="Source Entities" value={k?.source_entities}/><Metric label="Entity Mapping" value={k?.applied_entity_mappings}/><Metric label="Active Relations" value={k?.active_relations}/><Metric label="Relation Evidence" value={k?.relation_evidence_supports}/><Metric label="Wiki Claims" value={k?.wiki_claims}/><Metric label="Wiki Evidence" value={k?.wiki_evidence}/><Metric label="任务" value={`P${k?.pending_tasks ?? 0} / R${k?.running_tasks ?? 0} / F${k?.failed_tasks ?? 0}`}/></div><div class="rounded-xl border border-blue-100 bg-blue-50/60 p-3 text-xs leading-5 text-blue-700 dark:border-blue-900/60 dark:bg-blue-950/20 dark:text-blue-300">这些数字直接来自当前版本的 Raw/Evidence/Summary/Entity/Relation/Wiki 事实表和 compile_task，不用 Summary 数量推断其他阶段。</div><div class="flex flex-wrap gap-2">{canRecommend && <PrimaryButton onClick={() => void onGenerate()} disabled={generating || taskActive || selected.lifecycle !== 'ACTIVE'} icon="sparkles">{generating ? '正在排队…' : taskActive ? '知识生成中' : '生成知识'}</PrimaryButton>}{canRecommend && <SecondaryButton onClick={() => void onRecommend()} disabled={recommending || selected.lifecycle !== 'ACTIVE'} icon="sparkles">{recommending ? '处理中…' : selected.recommendation ? '已标记高价值' : '标记为高价值资料'}</SecondaryButton>}<SecondaryButton onClick={() => onNavigate('tasks')} icon="tasks">查看处理任务</SecondaryButton><SecondaryButton onClick={() => onNavigate('wiki')} icon="wiki">企业百科</SecondaryButton><SecondaryButton onClick={() => onNavigate('graph')} icon="graph">知识图谱</SecondaryButton></div></div>; }

function EvidenceTab({ detail, onOpenEvidence }: { detail: EnterpriseDocumentDetail | null; onOpenEvidence: (id: string) => Promise<void> }) { const blocks = detail?.blocks.filter(block => block.evidence_id) || []; if (!blocks.length) return <EmptyState icon="evidence" title="暂无 Evidence" description="当前预览范围内没有可打开的原文证据。"/>; return <div class="space-y-2">{blocks.map(block => <button key={block.evidence_id} onClick={() => void onOpenEvidence(block.evidence_id || '')} class="w-full rounded-xl border border-slate-200 p-3 text-left transition hover:border-blue-300 dark:border-slate-700"><div class="flex items-center justify-between gap-2"><span class="text-xs font-semibold text-blue-700 dark:text-blue-300">Evidence · Block {block.block_no}</span><Icon name="arrow" size={14} class="text-slate-400"/></div><div class="mt-2 line-clamp-3 text-xs leading-5 text-slate-500 dark:text-slate-400">{block.content}</div></button>)}</div>; }

function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (value: string) => void; placeholder: string }) { return <div class="relative"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={value} onInput={event => onChange((event.target as HTMLInputElement).value)} placeholder={placeholder} class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>; }
function LocalFilter({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) { return <label class="text-[11px] font-medium text-slate-400"><span class="sr-only">{label}</span><select value={value} onChange={event => onChange((event.target as HTMLSelectElement).value)} class="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-600 outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-300"><option value="ALL">全部{label}</option>{options.map(item => <option key={item} value={item}>{item}</option>)}</select></label>; }
function Detail({ label, value }: { label: string; value?: string | null }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] font-semibold uppercase tracking-[0.08em] text-slate-400">{label}</div><div class="mt-1 break-all text-sm font-medium text-slate-600 dark:text-slate-300">{value || '—'}</div></div>; }
function Metric({ label, value }: { label: string; value?: number | string }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 text-base font-semibold text-slate-700 dark:text-slate-200">{value ?? 0}</div></div>; }
