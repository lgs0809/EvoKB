import { useEffect, useState } from 'preact/hooks';
import { fetchEnterpriseBatches, fetchEnterpriseDocuments, fetchEnterpriseFacets, recommendEnterpriseDistill, type EnterpriseDocumentVersion, type EnterpriseFacets, type EnterpriseIngestionBatch } from '../lib/api';
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
  const [recentBatches, setRecentBatches] = useState<EnterpriseIngestionBatch[]>([]);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [resumeBatchId, setResumeBatchId] = useState('');
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

  const loadRecentBatches = async () => {
    try {
      const data = await fetchEnterpriseBatches({ page: 1, page_size: 6 });
      setRecentBatches(data.batches || []);
    } catch {
      setRecentBatches([]);
    }
  };

  useEffect(() => {
    void load(1);
    void loadRecentBatches();
    fetchEnterpriseFacets().then(setFacets).catch(() => undefined);
  }, []);

  const markDistill = async (versionId: string) => {
    setBusy(true);
    try { await recommendEnterpriseDistill(versionId); await load(page); if (selected) await select({ ...selected, recommendation: 'RECOMMENDED' }); onChanged(); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
    finally { setBusy(false); }
  };

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle eyebrow="Knowledge assets" title="文档与版本" description="大数据量按服务端分页。批量上传支持文件夹与 ZIP 后台解压，并在真正入库前逐文件确认 Metadata。" action={canUpload ? <PrimaryButton onClick={() => { setResumeBatchId(''); setUploadOpen(true); }} icon="documents">批量添加资料</PrimaryButton> : undefined}/>
      <div class="mt-5 grid gap-3 lg:grid-cols-[1.4fr_repeat(4,minmax(0,.7fr))_auto]">
        <div class="relative"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索文件名、知识名、公司、部门或类型" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <SelectFilter value={company} onChange={setCompany} values={facets.companies} label="全部公司"/>
        <SelectFilter value={department} onChange={setDepartment} values={facets.source_departments} label="全部部门"/>
        <SelectFilter value={documentType} onChange={setDocumentType} values={facets.document_types} label="全部类型"/>
        <SelectFilter value={category} onChange={setCategory} values={facets.material_categories} label="全部分类"/>
        <button onClick={() => void load(1)} class="rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white dark:bg-white dark:text-slate-950">筛选</button>
      </div>
      <div class="mt-3 flex gap-2 overflow-x-auto">{['ALL','ACTIVE','PENDING','FAILED','INACTIVE'].map(item => <button key={item} onClick={() => { setLifecycle(item); window.setTimeout(() => void load(1), 0); }} class={`whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold ${lifecycle === item ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300'}`}>{item}</button>)}</div>
    </section>

    {canUpload && recentBatches.length > 0 && <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div class="flex items-center justify-between gap-3"><div><div class="text-xs font-semibold uppercase tracking-[0.14em] text-blue-600 dark:text-blue-400">Managed ingestion</div><h3 class="mt-1 text-base font-semibold">最近上传批次</h3><p class="mt-1 text-xs text-slate-400">STAGED 批次可以继续逐文件调整属性；运行中的批次可以随时重新打开查看进度。</p></div><button onClick={() => void loadRecentBatches()} class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-semibold text-slate-500 dark:border-slate-700">刷新</button></div>
      <div class="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-3">{recentBatches.map(batch => <button key={batch.batch_id} onClick={() => { setUploadOpen(false); setResumeBatchId(batch.batch_id); }} class="rounded-2xl border border-slate-200 p-4 text-left transition hover:border-blue-300 hover:shadow-sm dark:border-slate-800"><div class="flex items-center justify-between gap-3"><StatusBadge value={batch.status}/><span class="font-mono text-[10px] text-slate-400">{shortID(batch.batch_id, 10)}</span></div><div class="mt-3 text-sm font-semibold text-slate-700 dark:text-slate-200">{batch.file_count} 个文件</div><div class="mt-1 text-xs text-slate-400">完成 {batch.succeeded_count} · 运行 {batch.running_count} · 失败 {batch.failed_count}</div><div class="mt-3 text-[11px] font-semibold text-blue-600 dark:text-blue-400">{batch.status === 'STAGED' ? '继续配置 →' : '查看进度 →'}</div></button>)}</div>
    </section>}

    <section class="grid gap-5 xl:grid-cols-[minmax(0,1.35fr)_minmax(360px,.65fr)]">
      <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {loading ? <div class="p-5"><LoadingBlock rows={7}/></div> : documents.length === 0 ? <div class="p-5"><EmptyState icon="documents" title="没有匹配的文档" description="调整服务端筛选条件，或添加新的企业资料。"/></div> : <div class="divide-y divide-slate-100 dark:divide-slate-800">{documents.map(item => <button key={item.version_id} onClick={() => void select(item)} class={`flex w-full items-start gap-4 px-4 py-4 text-left transition sm:px-5 ${selected?.version_id === item.version_id ? 'bg-blue-50/70 dark:bg-blue-950/20' : 'hover:bg-slate-50 dark:hover:bg-slate-950/30'}`}>
          <div class="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name="file" size={18}/></div>
          <div class="min-w-0 flex-1"><div class="flex flex-wrap items-center gap-2"><div class="truncate font-semibold text-slate-800 dark:text-slate-100">{item.logical_name}</div><StatusBadge value={item.lifecycle}/></div><div class="mt-1 truncate text-xs text-slate-400">{item.original_filename} · Version {item.version_no}</div><div class="mt-2 flex flex-wrap gap-2 text-[11px] text-slate-400">{item.company && <span>{item.company}</span>}{item.source_department && <span>· {item.source_department}</span>}{item.document_type && <span>· {item.document_type}</span>}{item.material_category && <span>· {item.material_category}</span>}</div></div>
          <div class="hidden text-right text-[11px] text-slate-400 sm:block"><div>{formatDate(item.parsed_at)}</div><div class="mt-1">{item.effective_hits} effective hits</div></div>
        </button>)}</div>}
        <Pager page={page} pageCount={pageCount} total={total} onPage={next => void load(next)}/>
      </div>

      <aside class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {!selected ? <EmptyState icon="documents" title="选择一个 Document Version" description="查看 Metadata、知识生命周期、版本历史与 Distillation 状态。"/> : detailLoading ? <LoadingBlock rows={6}/> : <DocumentDetail selected={selected} versions={versions} isAdmin={isAdmin} busy={busy} onSelect={setSelected} onDistill={markDistill} onNavigate={onNavigate}/>} 
      </aside>
    </section>
    {(uploadOpen || resumeBatchId) && canUpload && <BatchUploadDialog initialBatchId={resumeBatchId || undefined} onClose={() => { setUploadOpen(false); setResumeBatchId(''); void loadRecentBatches(); }} onChanged={() => { void load(1); void loadRecentBatches(); onChanged(); }} onError={onError}/>} 
  </div>;
}

function DocumentDetail({ selected, versions, isAdmin, busy, onSelect, onDistill, onNavigate }: { selected: EnterpriseDocumentVersion; versions: EnterpriseDocumentVersion[]; isAdmin: boolean; busy: boolean; onSelect: (item: EnterpriseDocumentVersion) => void; onDistill: (id: string) => Promise<void>; onNavigate: (section: EnterpriseSection) => void }) {
  const summaryReady = selected.summary_count > 0 && selected.grounded_summaries > 0 && selected.covered_summaries > 0;
  return <div class="space-y-5"><div><div class="flex items-center gap-2"><StatusBadge value={selected.lifecycle}/>{selected.recommendation && <StatusBadge value={selected.recommendation}/>}</div><h3 class="mt-3 text-lg font-semibold">{selected.logical_name}</h3><div class="mt-1 text-xs text-slate-400">{selected.original_filename} · Version {selected.version_no}</div></div>
    <Pipeline summaryReady={summaryReady} wikiReady={summaryReady} graphReady={summaryReady}/>
    <div class="grid grid-cols-2 gap-3 text-xs"><Info label="Company" value={selected.company || '—'}/><Info label="Department" value={selected.source_department || '—'}/><Info label="Document Type" value={selected.document_type || '—'}/><Info label="Material" value={selected.material_category || '—'}/><Info label="Document Date" value={selected.document_date ? formatDate(selected.document_date) : '—'}/><Info label="Version ID" value={shortID(selected.version_id, 12)}/></div>
    <div class="grid grid-cols-3 gap-2"><Quality label="Summary" value={selected.summary_count}/><Quality label="Grounding" value={selected.grounded_summaries}/><Quality label="Coverage" value={selected.covered_summaries}/></div>
    <div><div class="mb-2 flex items-center justify-between"><div class="text-xs font-semibold uppercase tracking-[0.12em] text-slate-400">Version history</div><span class="text-xs text-slate-400">{versions.length}</span></div><div class="max-h-56 space-y-2 overflow-y-auto">{versions.map(version => <button key={version.version_id} onClick={() => onSelect(version)} class={`flex w-full items-center justify-between rounded-xl border px-3 py-2.5 text-left ${version.version_id === selected.version_id ? 'border-blue-200 bg-blue-50 dark:border-blue-900 dark:bg-blue-950/30' : 'border-slate-100 bg-slate-50 dark:border-slate-800 dark:bg-slate-950/50'}`}><div><div class="text-xs font-semibold">Version {version.version_no}</div><div class="mt-0.5 text-[11px] text-slate-400">{formatDate(version.parsed_at)}</div></div><StatusBadge value={version.lifecycle}/></button>)}</div></div>
    {selected.recommendation === 'RECOMMENDED' ? <div class="rounded-xl border border-violet-200 bg-violet-50 p-3 text-xs text-violet-800 dark:border-violet-900/60 dark:bg-violet-950/30 dark:text-violet-300"><div class="font-semibold">已推荐高价值编译</div><div class="mt-1">{selected.recommendation_reason || '由有效使用或人工标记触发'}</div></div> : isAdmin && selected.lifecycle === 'ACTIVE' && <PrimaryButton onClick={() => void onDistill(selected.version_id)} disabled={busy} icon="sparkles">{busy ? '处理中…' : '标记为高价值 Source'}</PrimaryButton>}
    <div class="grid grid-cols-2 gap-2"><SecondaryButton onClick={() => onNavigate('evidence')} icon="evidence">查看 Evidence</SecondaryButton><SecondaryButton onClick={() => onNavigate('wiki')} icon="wiki">查看 Wiki</SecondaryButton></div>
  </div>;
}

function SelectFilter({ value, onChange, values, label }: { value: string; onChange: (value: string) => void; values: string[]; label: string }) { return <select value={value} onChange={event => onChange((event.target as HTMLSelectElement).value)} class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none dark:border-slate-700 dark:bg-slate-950"><option value="">{label}</option>{values.map(item => <option value={item} key={item}>{item}</option>)}</select>; }
function Pager({ page, pageCount, total, onPage }: { page: number; pageCount: number; total: number; onPage: (page: number) => void }) { return <div class="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => onPage(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => onPage(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>; }
function Info({ label, value }: { label: string; value: string }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 break-all text-xs font-medium text-slate-600 dark:text-slate-300">{value}</div></div>; }
function Quality({ label, value }: { label: string; value: number }) { return <div class="rounded-xl border border-slate-100 p-3 text-center dark:border-slate-800"><div class="text-lg font-semibold">{value}</div><div class="mt-0.5 text-[10px] uppercase tracking-wide text-slate-400">{label}</div></div>; }
