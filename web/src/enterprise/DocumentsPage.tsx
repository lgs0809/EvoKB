import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import { recommendEnterpriseDistill, uploadEnterpriseDocument, type EnterpriseDocumentVersion, type EnterpriseUploadResult } from '../lib/api';
import type { EnterpriseSection } from './EnterpriseShell';
import { EmptyState, Icon, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge, formatDate, shortID } from './ui';

export function DocumentsPage({ documents, onReload, onNavigate, onError, canUpload, isAdmin }: {
  documents: EnterpriseDocumentVersion[];
  onReload: () => Promise<void> | void;
  onNavigate: (section: EnterpriseSection) => void;
  onError: (message: string) => void;
  canUpload: boolean;
  isAdmin: boolean;
}) {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('ALL');
  const [selected, setSelected] = useState<EnterpriseDocumentVersion | null>(documents[0] || null);
  const [busy, setBusy] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadResult, setUploadResult] = useState<EnterpriseUploadResult | null>(null);

  const filtered = useMemo(() => documents.filter(item => {
    const matchesQuery = !query.trim() || [item.logical_name, item.original_filename, item.company, item.document_type, item.source_department].some(value => value?.toLowerCase().includes(query.trim().toLowerCase()));
    const matchesStatus = status === 'ALL' || item.lifecycle === status;
    return matchesQuery && matchesStatus;
  }), [documents, query, status]);

  const versions = useMemo(() => selected ? documents.filter(item => item.document_id === selected.document_id).sort((a, b) => b.version_no - a.version_no) : [], [documents, selected]);

  useEffect(() => {
    if (!selected && documents.length > 0) setSelected(documents[0]);
  }, [documents, selected]);

  const markDistill = async (versionId: string) => {
    setBusy(true);
    try {
      await recommendEnterpriseDistill(versionId);
      await onReload();
      onError('');
    } catch (err) {
      onError(String(err));
    } finally {
      setBusy(false);
    }
  };

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle eyebrow="Knowledge assets" title="文档与版本" description="每个 Document 可以有多个 Version；只有 ACTIVE Version 的 Raw Evidence 进入当前可信知识链。" action={canUpload ? <PrimaryButton onClick={() => { setUploadResult(null); setUploadOpen(true); }} icon="documents">添加文件</PrimaryButton> : undefined} />
      <div class="mt-5 flex flex-col gap-3 md:flex-row">
        <div class="relative flex-1"><Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"/><input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} placeholder="搜索文件名、公司、文档类型或部门" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></div>
        <div class="flex gap-2 overflow-x-auto">{['ALL', 'ACTIVE', 'PENDING', 'FAILED', 'INACTIVE'].map(item => <button key={item} onClick={() => setStatus(item)} class={`whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold transition ${status === item ? 'bg-slate-950 text-white dark:bg-white dark:text-slate-950' : 'bg-slate-100 text-slate-500 hover:text-slate-900 dark:bg-slate-800 dark:text-slate-300'}`}>{item}</button>)}</div>
      </div>
    </section>

    <section class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_430px]">
      <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        <div class="hidden grid-cols-[minmax(240px,1.5fr)_1fr_110px_150px_90px] gap-3 border-b border-slate-100 bg-slate-50/70 px-5 py-3 text-[11px] font-semibold uppercase tracking-[0.1em] text-slate-400 md:grid dark:border-slate-800 dark:bg-slate-950/40"><div>Document / Version</div><div>Metadata</div><div>Raw</div><div>Summary Quality</div><div class="text-right">Usage</div></div>
        <div class="divide-y divide-slate-100 dark:divide-slate-800">
          {filtered.map(doc => <button key={doc.version_id} onClick={() => setSelected(doc)} class={`grid w-full gap-3 px-5 py-4 text-left transition md:grid-cols-[minmax(240px,1.5fr)_1fr_110px_150px_90px] md:items-center ${selected?.version_id === doc.version_id ? 'bg-blue-50/70 dark:bg-blue-950/20' : 'hover:bg-slate-50 dark:hover:bg-slate-800/40'}`}>
            <div class="min-w-0"><div class="flex items-center gap-2"><div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-800"><Icon name="file" size={16}/></div><div class="min-w-0"><div class="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">{doc.logical_name}</div><div class="mt-0.5 truncate text-xs text-slate-400">v{doc.version_no} · {doc.original_filename}</div></div></div></div>
            <div class="min-w-0 text-xs"><div class="truncate text-slate-600 dark:text-slate-300">{doc.company || '未标注公司'}</div><div class="mt-1 truncate text-slate-400">{doc.document_type || '未分类'} · {doc.source_department || '—'}</div></div>
            <div><StatusBadge value={doc.lifecycle}/></div>
            <div class="text-xs"><div class="font-medium text-slate-700 dark:text-slate-200">{doc.summary_count} Summary</div><div class="mt-1 text-slate-400">G {doc.grounded_summaries} · C {doc.covered_summaries}</div></div>
            <div class="text-right"><div class="text-sm font-semibold">{doc.effective_hits}</div><div class="text-[10px] uppercase tracking-wide text-slate-400">effective</div></div>
          </button>)}
          {filtered.length === 0 && <div class="p-5"><EmptyState icon="documents" title="没有匹配的 Document Version" description="调整搜索条件或生命周期筛选后重试。" /></div>}
        </div>
      </div>

      <aside class="xl:sticky xl:top-28 xl:self-start">
        {selected ? <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
          <div class="border-b border-slate-100 bg-gradient-to-br from-slate-50 to-blue-50/60 p-5 dark:border-slate-800 dark:from-slate-900 dark:to-blue-950/20">
            <div class="flex items-start justify-between gap-3"><div class="min-w-0"><div class="text-xs font-semibold uppercase tracking-[0.14em] text-blue-600 dark:text-blue-400">Document detail</div><h3 class="mt-1 truncate text-lg font-semibold">{selected.logical_name}</h3><div class="mt-1 text-xs text-slate-400">Document {shortID(selected.document_id)} · Version {selected.version_no}</div></div><StatusBadge value={selected.lifecycle}/></div>
          </div>
          <div class="space-y-6 p-5">
            <div>
              <div class="mb-3 text-xs font-semibold uppercase tracking-[0.12em] text-slate-400">Knowledge lifecycle</div>
              <div class="space-y-0">
                <LifecycleStep icon="file" title="Original Document" detail={selected.original_filename} done />
                <LifecycleStep icon="layers" title="Raw Knowledge" detail={selected.parsed_at ? `Parsed ${formatDate(selected.parsed_at)}` : selected.lifecycle === 'ACTIVE' ? 'Active Raw Version' : 'Waiting for parser'} done={Boolean(selected.parsed_at) || selected.lifecycle === 'ACTIVE'} />
                <LifecycleStep icon="sparkles" title="Enterprise Summary" detail={selected.summary_count ? `${selected.grounded_summaries}/${selected.summary_count} Grounding · ${selected.covered_summaries}/${selected.summary_count} Coverage` : '尚未生成 Summary'} done={selected.summary_count > 0 && selected.grounded_summaries === selected.summary_count && selected.covered_summaries === selected.summary_count} />
                <LifecycleStep icon="wiki" title="Wiki / Graph" detail="通过 Summary Fact 与 Raw Evidence 关联；在知识资产页面独立查看" neutral />
                <LifecycleStep icon="shield" title="Evidence" detail="最终结果必须解析回本 Version 的 Raw Evidence" done={selected.lifecycle === 'ACTIVE'} last />
              </div>
            </div>

            <div class="grid grid-cols-2 gap-3 text-xs">
              <Info label="Company" value={selected.company || '—'}/><Info label="Department" value={selected.source_department || '—'}/><Info label="Document Type" value={selected.document_type || '—'}/><Info label="Material" value={selected.material_category || '—'}/><Info label="Document Date" value={selected.document_date ? formatDate(selected.document_date) : '—'}/><Info label="Version ID" value={shortID(selected.version_id, 12)}/>
            </div>

            <div>
              <div class="mb-2 flex items-center justify-between"><div class="text-xs font-semibold uppercase tracking-[0.12em] text-slate-400">Version history</div><span class="text-xs text-slate-400">{versions.length} versions</span></div>
              <div class="space-y-2">{versions.map(version => <button key={version.version_id} onClick={() => setSelected(version)} class={`flex w-full items-center justify-between rounded-xl border px-3 py-2.5 text-left ${version.version_id === selected.version_id ? 'border-blue-200 bg-blue-50 dark:border-blue-900 dark:bg-blue-950/30' : 'border-slate-100 bg-slate-50 dark:border-slate-800 dark:bg-slate-950/50'}`}><div><div class="text-xs font-semibold">Version {version.version_no}</div><div class="mt-0.5 text-[11px] text-slate-400">{formatDate(version.parsed_at)}</div></div><StatusBadge value={version.lifecycle}/></button>)}</div>
            </div>

            {selected.recommendation === 'RECOMMENDED' ? <div class="rounded-xl border border-violet-200 bg-violet-50 p-3 text-xs text-violet-800 dark:border-violet-900/60 dark:bg-violet-950/30 dark:text-violet-300"><div class="font-semibold">已推荐高价值编译</div><div class="mt-1">{selected.recommendation_reason || '由有效使用或人工标记触发'}</div></div> : isAdmin && selected.lifecycle === 'ACTIVE' && <PrimaryButton onClick={() => void markDistill(selected.version_id)} disabled={busy} icon="sparkles">{busy ? '处理中…' : '标记为高价值 Source'}</PrimaryButton>}
            <div class="grid grid-cols-2 gap-2"><SecondaryButton onClick={() => onNavigate('evidence')} icon="evidence">查看 Evidence</SecondaryButton><SecondaryButton onClick={() => onNavigate('wiki')} icon="wiki">查看 Wiki</SecondaryButton></div>
          </div>
        </div> : <EmptyState icon="documents" title="选择一个 Document Version" description="查看 Metadata、知识生命周期、版本历史与 Distillation 状态。" />}
      </aside>
    </section>
    {uploadOpen && canUpload && <UploadDialog result={uploadResult} onClose={() => setUploadOpen(false)} onUploaded={async result => { setUploadResult(result); await onReload(); }} onError={onError}/>}
  </div>;
}

function UploadDialog({ result, onClose, onUploaded, onError }: {
  result: EnterpriseUploadResult | null;
  onClose: () => void;
  onUploaded: (result: EnterpriseUploadResult) => Promise<void> | void;
  onError: (message: string) => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [logicalName, setLogicalName] = useState('');
  const [company, setCompany] = useState('');
  const [department, setDepartment] = useState('');
  const [documentType, setDocumentType] = useState('');
  const [category, setCategory] = useState('');
  const [documentDate, setDocumentDate] = useState('');
  const [tags, setTags] = useState('');
  const [uploading, setUploading] = useState(false);
  const inputClass = 'w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950';

  const submit = async () => {
    if (!file) return;
    setUploading(true);
    onError('');
    try {
      const uploaded = await uploadEnterpriseDocument(file, {
        logical_name: logicalName,
        company,
        source_department: department,
        document_type: documentType,
        material_category: category,
        document_date: documentDate,
        tags,
      });
      await onUploaded(uploaded);
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setUploading(false);
    }
  };

  return <div class="fixed inset-0 z-[80] flex items-center justify-center bg-slate-950/35 p-4 backdrop-blur-[1px]" onClick={onClose}>
    <div class="max-h-[92vh] w-full max-w-2xl overflow-y-auto rounded-[24px] border border-slate-200 bg-white shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={event => event.stopPropagation()}>
      <div class="sticky top-0 z-10 flex items-start justify-between gap-4 border-b border-slate-100 bg-white/95 p-5 backdrop-blur dark:border-slate-800 dark:bg-slate-900/95">
        <div><div class="text-xs font-semibold uppercase tracking-[0.14em] text-blue-600 dark:text-blue-400">Add knowledge source</div><h3 class="mt-1 text-xl font-semibold">添加企业文件</h3><p class="mt-1 text-xs leading-5 text-slate-400">上传后直接进入 MinerU → Raw Block → Embedding → OpenSearch，成功后激活新的 Document Version。</p></div>
        <button onClick={onClose} class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><Icon name="close" size={17}/></button>
      </div>
      <div class="space-y-5 p-5">
        {!result ? <>
          <label class={`flex min-h-32 cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed p-5 text-center transition ${file ? 'border-blue-300 bg-blue-50/60 dark:border-blue-800 dark:bg-blue-950/20' : 'border-slate-200 bg-slate-50 hover:border-blue-300 dark:border-slate-700 dark:bg-slate-950/50'}`}>
            <input type="file" class="hidden" onChange={event => setFile((event.target as HTMLInputElement).files?.[0] || null)}/>
            <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-white text-blue-600 shadow-sm dark:bg-slate-800 dark:text-blue-300"><Icon name="documents" size={19}/></div>
            <div class="mt-3 text-sm font-semibold">{file ? file.name : '选择要入库的文件'}</div>
            <div class="mt-1 text-xs text-slate-400">{file ? `${(file.size / 1024 / 1024).toFixed(2)} MiB` : 'PDF / Word / PPT / Excel / 图片等由当前 MinerU Pipeline 解析'}</div>
          </label>
          <div class="grid gap-3 sm:grid-cols-2">
            <UploadField label="知识名称" hint="留空使用文件名"><input value={logicalName} onInput={event => setLogicalName((event.target as HTMLInputElement).value)} class={inputClass}/></UploadField>
            <UploadField label="公司"><input value={company} onInput={event => setCompany((event.target as HTMLInputElement).value)} class={inputClass}/></UploadField>
            <UploadField label="来源部门"><input value={department} onInput={event => setDepartment((event.target as HTMLInputElement).value)} class={inputClass}/></UploadField>
            <UploadField label="文档类型"><input value={documentType} onInput={event => setDocumentType((event.target as HTMLInputElement).value)} class={inputClass}/></UploadField>
            <UploadField label="材料分类"><input value={category} onInput={event => setCategory((event.target as HTMLInputElement).value)} class={inputClass}/></UploadField>
            <UploadField label="文档日期"><input type="date" value={documentDate} onInput={event => setDocumentDate((event.target as HTMLInputElement).value)} class={inputClass}/></UploadField>
            <div class="sm:col-span-2"><UploadField label="标签" hint="多个标签用英文逗号分隔"><input value={tags} onInput={event => setTags((event.target as HTMLInputElement).value)} class={inputClass}/></UploadField></div>
          </div>
          {uploading && <div class="rounded-xl border border-blue-200 bg-blue-50 p-4 text-xs leading-6 text-blue-800 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-300"><div class="flex items-center gap-2 font-semibold"><Icon name="refresh" size={15} class="animate-spin"/>正在解析并建立 Raw 检索索引…</div><div class="mt-1 opacity-80">这个 V1 上传是同步链路，浏览器会等待 MinerU、Embedding 和 OpenSearch 完成；不要关闭页面。</div></div>}
          <div class="flex justify-end gap-2"><SecondaryButton onClick={onClose}>取消</SecondaryButton><PrimaryButton onClick={() => void submit()} disabled={!file || uploading} icon="documents">{uploading ? '处理中…' : '上传并解析'}</PrimaryButton></div>
        </> : <div class="space-y-5">
          <div class="flex items-start gap-4 rounded-2xl border border-emerald-200 bg-emerald-50 p-5 text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/30 dark:text-emerald-300"><div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-emerald-600 text-white"><Icon name="check" size={18}/></div><div><div class="font-semibold">文档已经进入 ACTIVE Raw 生命周期</div><div class="mt-1 text-xs leading-5 opacity-80">Document Version 已持久化，并完成 Raw Block、Evidence、Embedding 与 OpenSearch 索引。</div></div></div>
          <div class="grid grid-cols-2 gap-3 sm:grid-cols-4"><UploadStat label="Version" value={`v${result.version_no}`}/><UploadStat label="Blocks" value={String(result.block_count)}/><UploadStat label="Chunks" value={String(result.chunk_count)}/><UploadStat label="Evidence" value={String(result.evidence_count)}/></div>
          {(result.warnings || []).length > 0 && <div class="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">{result.warnings?.join('；')}</div>}
          <div class="flex justify-end"><PrimaryButton onClick={onClose} icon="check">完成</PrimaryButton></div>
        </div>}
      </div>
    </div>
  </div>;
}

function UploadField({ label, hint, children }: { label: string; hint?: string; children: ComponentChildren }) {
  return <label class="block text-xs font-medium text-slate-500"><div class="mb-1.5 flex items-center justify-between gap-2"><span>{label}</span>{hint && <span class="font-normal text-slate-400">{hint}</span>}</div>{children}</label>;
}

function UploadStat({ label, value }: { label: string; value: string }) {
  return <div class="rounded-xl bg-slate-50 p-3 text-center dark:bg-slate-950/50"><div class="text-lg font-semibold">{value}</div><div class="mt-1 text-[10px] uppercase tracking-wide text-slate-400">{label}</div></div>;
}

function LifecycleStep({ icon, title, detail, done, neutral, last }: { icon: 'file' | 'layers' | 'sparkles' | 'wiki' | 'shield'; title: string; detail: string; done?: boolean; neutral?: boolean; last?: boolean }) {
  return <div class="flex gap-3"><div class="flex flex-col items-center"><div class={`flex h-9 w-9 items-center justify-center rounded-xl border ${done ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300' : neutral ? 'border-blue-200 bg-blue-50 text-blue-600 dark:border-blue-900/60 dark:bg-blue-950/40 dark:text-blue-300' : 'border-slate-200 bg-slate-50 text-slate-400 dark:border-slate-700 dark:bg-slate-800'}`}><Icon name={icon} size={16}/></div>{!last && <div class={`h-8 w-px ${done ? 'bg-emerald-200 dark:bg-emerald-900' : 'bg-slate-200 dark:bg-slate-700'}`}/>}</div><div class="min-w-0 pb-4"><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">{title}</div><div class="mt-0.5 text-xs leading-5 text-slate-400">{detail}</div></div></div>;
}

function Info({ label, value }: { label: string; value: string }) {
  return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 break-words font-medium text-slate-700 dark:text-slate-200">{value}</div></div>;
}
