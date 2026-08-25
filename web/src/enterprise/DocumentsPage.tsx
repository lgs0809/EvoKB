import { useEffect, useState } from 'preact/hooks';
import { fetchEnterpriseDocuments, fetchEnterpriseFacets, recommendEnterpriseKnowledgeGeneration, type EnterpriseDocumentVersion, type EnterpriseFacets } from '../lib/api';
import { BatchUploadDialog } from './BatchUploadDialog';
import type { EnterpriseSection } from './EnterpriseShell';
import { EmptyState, Icon, LoadingBlock, Pipeline, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge, formatDate, shortID } from './ui';

export function DocumentsPage({ onNavigate, onError, canUpload, isAdmin, onChanged }: {
  onNavigate: (section: EnterpriseSection) => void;
  onError: (message: string) => void;
  canUpload: boolean;
  isAdmin: boolean;
  onChanged: () => void;
}) {
  const [documents, setDocuments] = useState<EnterpriseDocumentVersion[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState('');
  const [lifecycle, setLifecycle] = useState('ALL');
  const [company, setCompany] = useState('');
  const [department, setDepartment] = useState('');
  const [documentType, setDocumentType] = useState('');
  const [category, setCategory] = useState('');
  const [facets, setFacets] = useState<EnterpriseFacets>({ companies: [], source_departments: [], document_types: [], material_categories: [] });
  const [selected, setSelected] = useState<EnterpriseDocumentVersion | null>(null);
  const [versions, setVersions] = useState<EnterpriseDocumentVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const pageSize = 30;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  const load = async (nextPage = page) => {
    setLoading(true);
    try {
      const data = await fetchEnterpriseDocuments({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, lifecycle, company: company || undefined, source_department: department || undefined, document_type: documentType || undefined, material_category: category || undefined });
      setDocuments(data.documents || []);
      setTotal(data.total || 0);
      setPage(nextPage);
      if (selected && !(data.documents || []).some(item => item.version_id === selected.version_id)) { setSelected(null); setVersions([]); }
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setLoading(false); }
  };

  const select = async (item: EnterpriseDocumentVersion) => {
    setSelected(item);
    setDetailLoading(true);
    try {
      const data = await fetchEnterpriseDocuments({ document_id: item.document_id, page: 1, page_size: 100 });
      setVersions((data.documents || []).sort((a, b) => b.version_no - a.version_no));
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setDetailLoading(false); }
  };

  useEffect(() => {
    void load(1);
    fetchEnterpriseFacets().then(setFacets).catch(() => undefined);
  }, []);

  const recommendGeneration = async (versionId: string) => {
    setBusy(true);
    try { await recommendEnterpriseKnowledgeGeneration(versionId); await load(page); if (selected) await select({ ...selected, recommendation: 'RECOMMENDED' }); onChanged(); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setBusy(false); }
  };

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle title="资料" description="管理资料及其版本。" action={canUpload ? <PrimaryButton onClick={() => setUploadOpen(true)} icon="documents">添加资料</PrimaryButton> : undefined}/>
      <div class="mt-5 grid gap-3 lg:grid-cols-[1.4fr_repeat(4,minmax(0,.7fr))_auto]">
        <div class="relative"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索文件名、知识名、公司、部门或类型" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <SelectFilter value={company} onChange={setCompany} values={facets.companies} label="全部公司"/>
        <SelectFilter value={department} onChange={setDepartment} values={facets.source_departments} label="全部部门"/>
        <SelectFilter value={documentType} onChange={setDocumentType} values={facets.document_types} label="全部类型"/>
        <SelectFilter value={category} onChange={setCategory} values={facets.material_categories} label="全部分类"/>
        <button onClick={() => void load(1)} class="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm shadow-blue-600/15 transition hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400">筛选</button>
      </div>
      <div class="mt-3 flex gap-2 overflow-x-auto">{['ALL','ACTIVE','PENDING','FAILED','INACTIVE'].map(item => <button key={item} onClick={() => { setLifecycle(item); window.setTimeout(() => void load(1), 0); }} class={`whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold ${lifecycle === item ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300'}`}>{({ ALL: '全部', ACTIVE: '可用', PENDING: '等待处理', FAILED: '失败', INACTIVE: '已停用' } as Record<string, string>)[item]}</button>)}</div>
    </section>

    <section class="grid gap-5 xl:grid-cols-[minmax(0,1.35fr)_minmax(360px,.65fr)]">
      <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {loading ? <div class="p-5"><LoadingBlock rows={7}/></div> : documents.length === 0 ? <div class="p-5"><EmptyState icon="documents" title="没有匹配的资料" description="调整筛选条件或添加资料。"/></div> : <div class="divide-y divide-slate-100 dark:divide-slate-800">{documents.map(item => <button key={item.version_id} onClick={() => void select(item)} class={`flex w-full items-start gap-4 px-4 py-4 text-left transition sm:px-5 ${selected?.version_id === item.version_id ? 'bg-blue-50/70 dark:bg-blue-950/20' : 'hover:bg-slate-50 dark:hover:bg-slate-950/30'}`}>
          <div class="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name="file" size={18}/></div>
          <div class="min-w-0 flex-1"><div class="flex flex-wrap items-center gap-2"><div class="truncate font-semibold text-slate-800 dark:text-slate-100">{item.logical_name}</div><StatusBadge value={item.lifecycle}/></div><div class="mt-1 truncate text-xs text-slate-400">{item.original_filename} · 版本 {item.version_no}</div><div class="mt-2 flex flex-wrap gap-2 text-[11px] text-slate-400">{item.company && <span>{item.company}</span>}{item.source_department && <span>· {item.source_department}</span>}{item.document_type && <span>· {item.document_type}</span>}{item.material_category && <span>· {item.material_category}</span>}</div></div>
          <div class="hidden text-right text-[11px] text-slate-400 sm:block"><div>{formatDate(item.parsed_at)}</div><div class="mt-1">{item.effective_hits} 次有效引用</div></div>
        </button>)}</div>}
        <Pager page={page} pageCount={pageCount} total={total} onPage={next => void load(next)}/>
      </div>

      <aside class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {!selected ? <EmptyState icon="documents" title="选择一个资料版本" description="查看资料信息、版本历史和知识生成状态。"/> : detailLoading ? <LoadingBlock rows={6}/> : <DocumentDetail selected={selected} versions={versions} isAdmin={isAdmin} busy={busy} onSelect={setSelected} onRecommendGeneration={recommendGeneration} onNavigate={onNavigate}/>}
      </aside>
    </section>
    {uploadOpen && canUpload && <BatchUploadDialog onClose={() => setUploadOpen(false)} onChanged={() => { void load(1); onChanged(); }} onError={onError}/>}
  </div>;
}

function DocumentDetail({ selected, versions, isAdmin, busy, onSelect, onRecommendGeneration, onNavigate }: { selected: EnterpriseDocumentVersion; versions: EnterpriseDocumentVersion[]; isAdmin: boolean; busy: boolean; onSelect: (item: EnterpriseDocumentVersion) => void; onRecommendGeneration: (id: string) => Promise<void>; onNavigate: (section: EnterpriseSection) => void }) {
  const summaryReady = selected.summary_count > 0 && selected.grounded_summaries > 0 && selected.covered_summaries > 0;
  return <div class="space-y-5"><div><div class="flex items-center gap-2"><StatusBadge value={selected.lifecycle}/>{selected.recommendation && <StatusBadge value={selected.recommendation}/>}</div><h3 class="mt-3 text-lg font-semibold">{selected.logical_name}</h3><div class="mt-1 text-xs text-slate-400">{selected.original_filename} · 版本 {selected.version_no}</div></div>
    <Pipeline summaryReady={summaryReady}/>
    <div class="grid grid-cols-2 gap-3 text-xs"><Info label="公司" value={selected.company || '—'}/><Info label="来源部门" value={selected.source_department || '—'}/><Info label="文档类型" value={selected.document_type || '—'}/><Info label="材料分类" value={selected.material_category || '—'}/><Info label="文档日期" value={selected.document_date ? formatDate(selected.document_date) : '—'}/><Info label="版本编号" value={shortID(selected.version_id, 12)}/></div>
    <div class="grid grid-cols-3 gap-2"><Quality label="摘要" value={selected.summary_count}/><Quality label="事实校验" value={selected.grounded_summaries}/><Quality label="完整性检查" value={selected.covered_summaries}/></div>
    <div><div class="mb-2 flex items-center justify-between"><div class="text-xs font-semibold tracking-[0.12em] text-slate-400">版本历史</div><span class="text-xs text-slate-400">{versions.length}</span></div><div class="max-h-56 space-y-2 overflow-y-auto">{versions.map(version => <button key={version.version_id} onClick={() => onSelect(version)} class={`flex w-full items-center justify-between rounded-xl border px-3 py-2.5 text-left ${version.version_id === selected.version_id ? 'border-blue-200 bg-blue-50 dark:border-blue-900 dark:bg-blue-950/30' : 'border-slate-100 bg-slate-50 dark:border-slate-800 dark:bg-slate-950/50'}`}><div><div class="text-xs font-semibold">版本 {version.version_no}</div><div class="mt-0.5 text-[11px] text-slate-400">{formatDate(version.parsed_at)}</div></div><StatusBadge value={version.lifecycle}/></button>)}</div></div>
    {selected.recommendation === 'RECOMMENDED' ? <div class="rounded-xl border border-violet-200 bg-violet-50 p-3 text-xs text-violet-800 dark:border-violet-900/60 dark:bg-violet-950/30 dark:text-violet-300"><div class="font-semibold">建议生成高级知识</div><div class="mt-1">{selected.recommendation_reason || '由有效引用或管理员标记触发'}</div></div> : isAdmin && selected.lifecycle === 'ACTIVE' && <PrimaryButton onClick={() => void onRecommendGeneration(selected.version_id)} disabled={busy} icon="sparkles">{busy ? '处理中…' : '建议生成高级知识'}</PrimaryButton>}
    <div class="grid grid-cols-2 gap-2"><SecondaryButton onClick={() => onNavigate('evidence')} icon="evidence">查看证据</SecondaryButton><SecondaryButton onClick={() => onNavigate('wiki')} icon="wiki">查看企业百科</SecondaryButton></div>
  </div>;
}

function SelectFilter({ value, onChange, values, label }: { value: string; onChange: (value: string) => void; values: string[]; label: string }) { return <select value={value} onChange={event => onChange((event.target as HTMLSelectElement).value)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none dark:border-slate-700 dark:bg-slate-950"><option value="">{label}</option>{values.map(item => <option value={item} key={item}>{item}</option>)}</select>; }
function Pager({ page, pageCount, total, onPage }: { page: number; pageCount: number; total: number; onPage: (page: number) => void }) { return <div class="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => onPage(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => onPage(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>; }
function Info({ label, value }: { label: string; value: string }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 break-all text-xs font-medium text-slate-600 dark:text-slate-300">{value}</div></div>; }
function Quality({ label, value }: { label: string; value: number }) { return <div class="rounded-xl border border-slate-100 p-3 text-center dark:border-slate-800"><div class="text-lg font-semibold">{value}</div><div class="mt-0.5 text-[10px] uppercase tracking-wide text-slate-400">{label}</div></div>; }
