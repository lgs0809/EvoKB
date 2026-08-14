import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { createEnterpriseBatch, fetchEnterpriseBatch, retryEnterpriseBatchFile, startEnterpriseBatch, updateEnterpriseBatchFile, type EnterpriseIngestionBatch, type EnterpriseIngestionFile } from '../lib/api';
import { Icon, PrimaryButton, SecondaryButton, StatusBadge, formatNumber } from './ui';

type LocalFile = EnterpriseIngestionFile & { tags_text: string };

export function BatchUploadDialog({ onClose, onChanged, onError, initialBatchId }: { onClose: () => void; onChanged: () => void; onError: (message: string) => void; initialBatchId?: string }) {
  const [selected, setSelected] = useState<Array<{ file: File; relativePath: string }>>([]);
  const [batch, setBatch] = useState<EnterpriseIngestionBatch | null>(null);
  const [drafts, setDrafts] = useState<Record<string, LocalFile>>({});
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [rejected, setRejected] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [company, setCompany] = useState('');
  const [department, setDepartment] = useState('');
  const [documentType, setDocumentType] = useState('');
  const [category, setCategory] = useState('');
  const [documentDate, setDocumentDate] = useState('');
  const [tags, setTags] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);
  const directoryInput = useRef<HTMLInputElement>(null);
  const terminal = batch && ['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(batch.status);

  const hydrate = (value: EnterpriseIngestionBatch) => {
    setBatch(value);
    const next: Record<string, LocalFile> = {};
    for (const item of value.files || []) next[item.file_id] = { ...item, tags_text: (item.manual_tags || []).join(', ') };
    setDrafts(next);
  };

  useEffect(() => {
    if (!initialBatchId) return;
    let cancelled = false;
    setBusy(true);
    fetchEnterpriseBatch(initialBatchId).then(value => { if (!cancelled) hydrate(value); }).catch(err => onError(String(err instanceof Error ? err.message : err))).finally(() => { if (!cancelled) setBusy(false); });
    return () => { cancelled = true; };
  }, [initialBatchId]);

  useEffect(() => {
    if (!batch || !['QUEUED', 'RUNNING'].includes(batch.status)) return;
    const timer = window.setInterval(async () => {
      try {
        const next = await fetchEnterpriseBatch(batch.batch_id);
        hydrate(next);
        if (['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(next.status)) onChanged();
      } catch (err) {
        onError(String(err instanceof Error ? err.message : err));
      }
    }, 2000);
    return () => window.clearInterval(timer);
  }, [batch?.batch_id, batch?.status]);

  const addFiles = (list: FileList | File[]) => {
    const incoming = Array.from(list).map(file => ({ file, relativePath: ((file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name) }));
    setSelected(prev => {
      const seen = new Set(prev.map(item => `${item.relativePath}:${item.file.size}:${item.file.lastModified}`));
      const out = [...prev];
      for (const item of incoming) {
        const key = `${item.relativePath}:${item.file.size}:${item.file.lastModified}`;
        if (!seen.has(key)) { seen.add(key); out.push(item); }
      }
      return out;
    });
  };

  const stage = async () => {
    if (!selected.length) return;
    setBusy(true);
    try {
      const result = await createEnterpriseBatch(selected, { company, source_department: department, document_type: documentType, material_category: category, document_date: documentDate, tags });
      hydrate(result.batch);
      setRejected(result.rejected || []);
      setDirty(new Set());
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
  };

  const patchDraft = (fileId: string, patch: Partial<LocalFile>) => {
    setDrafts(prev => ({ ...prev, [fileId]: { ...prev[fileId], ...patch } }));
    setDirty(prev => new Set(prev).add(fileId));
  };

  const saveDirty = async () => {
    const ids = Array.from(dirty);
    if (!ids.length) return;
    for (let offset = 0; offset < ids.length; offset += 10) {
      const chunk = ids.slice(offset, offset + 10);
      await Promise.all(chunk.map(async id => {
        const item = drafts[id];
        await updateEnterpriseBatchFile(id, {
          logical_name: item.logical_name,
          source_department: item.source_department || '',
          material_category: item.material_category || '',
          company: item.company || '',
          document_type: item.document_type || '',
          document_date: item.document_date ? item.document_date.slice(0, 10) : '',
          manual_tags: item.tags_text.split(',').map(value => value.trim()).filter(Boolean),
        });
      }));
    }
    setDirty(new Set());
  };

  const start = async () => {
    if (!batch) return;
    setBusy(true);
    try {
      await saveDirty();
      const next = await startEnterpriseBatch(batch.batch_id);
      const detailed = await fetchEnterpriseBatch(next.batch_id);
      hydrate(detailed);
      onChanged();
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
  };

  const retry = async (item: LocalFile) => {
    try {
      await retryEnterpriseBatchFile(item.file_id);
      if (batch) hydrate(await fetchEnterpriseBatch(batch.batch_id));
      onError('');
    } catch (err) { onError(String(err instanceof Error ? err.message : err)); }
  };

  const totalSize = useMemo(() => selected.reduce((sum, item) => sum + item.file.size, 0), [selected]);

  return <div class="fixed inset-0 z-[80] flex items-center justify-center bg-slate-950/45 p-3 backdrop-blur-[2px] sm:p-6" onClick={onClose}>
    <div class="flex max-h-[94vh] w-full max-w-7xl flex-col overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={event => event.stopPropagation()}>
      <div class="flex items-start justify-between gap-4 border-b border-slate-100 px-5 py-4 dark:border-slate-800 sm:px-6"><div><div class="text-xs font-semibold uppercase tracking-[0.16em] text-blue-600 dark:text-blue-400">Managed ingestion</div><h3 class="mt-1 text-xl font-semibold">批量添加知识资料</h3><p class="mt-1 text-xs leading-5 text-slate-400">支持多文件、文件夹和 ZIP。ZIP 在服务端安全解压；真正入库前可以逐个修改每个文件的企业属性。</p></div><button onClick={onClose} class="rounded-xl p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><Icon name="close"/></button></div>

      <div class="flex-1 overflow-y-auto p-5 sm:p-6">
        {!batch ? <div class="grid gap-5 xl:grid-cols-[1.05fr_.95fr]">
          <div class="space-y-4">
            <div onDragOver={event => event.preventDefault()} onDrop={event => { event.preventDefault(); const files = event.dataTransfer?.files; if (files) addFiles(files); }} class="flex min-h-56 flex-col items-center justify-center rounded-3xl border-2 border-dashed border-slate-300 bg-slate-50 p-6 text-center transition hover:border-blue-400 dark:border-slate-700 dark:bg-slate-950/50">
              <div class="rounded-2xl bg-white p-4 text-blue-600 shadow-sm dark:bg-slate-800"><Icon name="documents" size={28}/></div><div class="mt-4 text-base font-semibold">拖拽文件、ZIP 或文件夹到这里</div><div class="mt-1 max-w-md text-xs leading-5 text-slate-400">浏览器目录选择会保留 relative path；ZIP 会上传到服务器后解压。支持 PDF / Office / 图片 / Markdown / Text。</div>
              <div class="mt-5 flex flex-wrap justify-center gap-2"><SecondaryButton onClick={() => fileInput.current?.click()} icon="file">选择多个文件</SecondaryButton><SecondaryButton onClick={() => directoryInput.current?.click()} icon="documents">选择文件夹</SecondaryButton></div>
              <input ref={fileInput} type="file" multiple class="hidden" onChange={event => { if ((event.target as HTMLInputElement).files) addFiles((event.target as HTMLInputElement).files!); }}/>
              <input ref={directoryInput} type="file" multiple class="hidden" {...({ webkitdirectory: '', directory: '' } as Record<string, string>)} onChange={event => { if ((event.target as HTMLInputElement).files) addFiles((event.target as HTMLInputElement).files!); }}/>
            </div>
            {selected.length > 0 && <div class="rounded-2xl border border-slate-200 dark:border-slate-800"><div class="flex items-center justify-between border-b border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800"><span>已选择 {selected.length} 个输入 · {formatBytes(totalSize)}</span><button onClick={() => setSelected([])} class="font-semibold text-rose-500">清空</button></div><div class="max-h-64 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">{selected.map((item, index) => <div key={`${item.relativePath}-${index}`} class="flex items-center gap-3 px-4 py-2.5 text-xs"><Icon name="file" size={15} class="shrink-0 text-slate-400"/><div class="min-w-0 flex-1 truncate text-slate-600 dark:text-slate-300">{item.relativePath}</div><span class="shrink-0 text-slate-400">{formatBytes(item.file.size)}</span></div>)}</div></div>}
          </div>
          <div class="rounded-2xl border border-slate-200 p-5 dark:border-slate-800"><div class="text-sm font-semibold">批次默认属性</div><div class="mt-1 text-xs leading-5 text-slate-400">这些值会复制给解压后的每个文件，下一步仍可逐文件覆盖。</div><div class="mt-5 grid gap-3 sm:grid-cols-2"><Field label="公司" value={company} onInput={setCompany}/><Field label="来源部门" value={department} onInput={setDepartment}/><Field label="文档类型" value={documentType} onInput={setDocumentType}/><Field label="材料分类" value={category} onInput={setCategory}/><Field label="文档日期" value={documentDate} onInput={setDocumentDate} type="date"/><Field label="Tags（逗号分隔）" value={tags} onInput={setTags}/></div><div class="mt-5 rounded-xl bg-blue-50 p-3 text-xs leading-5 text-blue-800 dark:bg-blue-950/30 dark:text-blue-300"><b>两阶段入库：</b>现在只上传/解压到安全 staging，不运行 MinerU。确认每个文件属性后，才启动后台解析、Embedding、索引和 Version 激活。</div></div>
        </div> : <BatchEditor batch={batch} drafts={drafts} dirty={dirty} rejected={rejected} onPatch={patchDraft} onRetry={retry}/>} 
      </div>

      <div class="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 bg-slate-50 px-5 py-4 dark:border-slate-800 dark:bg-slate-950/50 sm:px-6"><div class="text-xs text-slate-400">{batch ? <>Batch {batch.batch_id.slice(0, 12)} · <StatusBadge value={batch.status}/></> : `${selected.length} inputs ready`}</div><div class="flex flex-wrap gap-2"><SecondaryButton onClick={onClose}>{terminal ? '完成' : '关闭到后台'}</SecondaryButton>{!batch ? <PrimaryButton onClick={() => void stage()} disabled={busy || !selected.length} icon="documents">{busy ? '上传并解压…' : '上传并进入属性确认'}</PrimaryButton> : batch.status === 'STAGED' ? <>{dirty.size > 0 && <SecondaryButton onClick={() => void saveAndRefresh()} disabled={busy} icon="check">{busy ? '保存中…' : `保存 ${dirty.size} 项并重新检测`}</SecondaryButton>}<PrimaryButton onClick={() => void start()} disabled={busy || dirty.size > 0} icon="sparkles">{dirty.size > 0 ? '先保存属性' : busy ? '启动中…' : '开始后台入库'}</PrimaryButton></> : null}</div></div>
    </div>
  </div>;
}

function BatchEditor({ batch, drafts, dirty, rejected, onPatch, onRetry }: { batch: EnterpriseIngestionBatch; drafts: Record<string, LocalFile>; dirty: Set<string>; rejected: string[]; onPatch: (fileId: string, patch: Partial<LocalFile>) => void; onRetry: (item: LocalFile) => void }) {
  const files = batch.files || [];
  const editable = batch.status === 'STAGED';
  return <div class="space-y-4">
    <div class="grid gap-3 sm:grid-cols-3 lg:grid-cols-6"><MiniStat label="文件" value={batch.file_count}/><MiniStat label="待处理" value={batch.staged_count + batch.queued_count}/><MiniStat label="运行" value={batch.running_count}/><MiniStat label="完成" value={batch.succeeded_count}/><MiniStat label="失败" value={batch.failed_count}/><MiniStat label="已修改属性" value={dirty.size}/></div>
    {rejected.length > 0 && <details class="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-300"><summary class="cursor-pointer font-semibold">{rejected.length} 个条目未进入批次</summary><ul class="mt-2 space-y-1">{rejected.map((item, i) => <li key={i}>{item}</li>)}</ul></details>}
    <div class="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800"><table class="min-w-[1500px] w-full text-xs"><thead class="sticky top-0 bg-slate-50 text-left text-[10px] font-semibold uppercase tracking-wide text-slate-400 dark:bg-slate-950"><tr><th class="px-3 py-3">File / Stage</th><th class="px-3 py-3">知识名称</th><th class="px-3 py-3">公司</th><th class="px-3 py-3">部门</th><th class="px-3 py-3">类型</th><th class="px-3 py-3">分类</th><th class="px-3 py-3">日期</th><th class="px-3 py-3">Tags</th><th class="px-3 py-3">操作</th></tr></thead><tbody class="divide-y divide-slate-100 dark:divide-slate-800">{files.map(raw => { const item = drafts[raw.file_id] || { ...raw, tags_text: (raw.manual_tags || []).join(', ') }; return <tr key={item.file_id} class="align-top"><td class="max-w-xs px-3 py-3"><div class="flex items-center gap-2"><StatusBadge value={item.status}/><span class="font-semibold text-slate-600 dark:text-slate-300">{item.progress_stage}</span></div><div class="mt-2 break-all text-slate-500">{item.relative_path}</div><BehaviorHint item={item} dirty={dirty.has(item.file_id)} onUseExisting={() => item.duplicate_logical_name && onPatch(item.file_id, { logical_name: item.duplicate_logical_name })}/>{item.error_message && <div class="mt-2 max-w-xs text-rose-500">{item.error_message}</div>}</td><EditCell value={item.logical_name} disabled={!editable} onInput={value => onPatch(item.file_id,{ logical_name:value })}/><EditCell value={item.company || ''} disabled={!editable} onInput={value => onPatch(item.file_id,{ company:value })}/><EditCell value={item.source_department || ''} disabled={!editable} onInput={value => onPatch(item.file_id,{ source_department:value })}/><EditCell value={item.document_type || ''} disabled={!editable} onInput={value => onPatch(item.file_id,{ document_type:value })}/><EditCell value={item.material_category || ''} disabled={!editable} onInput={value => onPatch(item.file_id,{ material_category:value })}/><EditCell value={item.document_date?.slice(0,10) || ''} type="date" disabled={!editable} onInput={value => onPatch(item.file_id,{ document_date:value })}/><EditCell value={item.tags_text} disabled={!editable} onInput={value => onPatch(item.file_id,{ tags_text:value })}/><td class="px-3 py-3">{item.status === 'FAILED' ? <button onClick={() => void onRetry(item)} class="rounded-lg border border-rose-200 px-2.5 py-1.5 font-semibold text-rose-600 hover:bg-rose-50 dark:border-rose-900">重试</button> : dirty.has(item.file_id) ? <span class="text-blue-500">待保存</span> : <span class="text-slate-300">—</span>}</td></tr>; })}</tbody></table></div>
  </div>;
}

function BehaviorHint({ item, dirty, onUseExisting }: { item: LocalFile; dirty: boolean; onUseExisting: () => void }) {
  if (dirty) return <div class="mt-2 text-[11px] font-medium text-blue-500">知识名称已修改，保存后重新检测 Version / 重复内容。</div>;
  switch (item.expected_behavior) {
    case 'REUSE_VERSION': return <div class="mt-2 rounded-lg bg-slate-100 px-2 py-1.5 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300">内容与该知识现有 Version 完全一致，将直接复用现有 Version。</div>;
    case 'DUPLICATE_CONTENT': return <div class="mt-2 rounded-lg bg-amber-50 px-2 py-1.5 text-[11px] text-amber-700 dark:bg-amber-950/30 dark:text-amber-300"><div>检测到相同内容已存在于「{item.duplicate_logical_name || '其他知识'}」{item.duplicate_version_lifecycle ? ` · ${item.duplicate_version_lifecycle}` : ''}；仍可继续作为当前知识入库。</div>{item.duplicate_logical_name && <button type="button" onClick={onUseExisting} class="mt-2 rounded-md bg-amber-600 px-2 py-1 font-semibold text-white">沿用现有知识名</button>}</div>;
    case 'CREATE_VERSION': return <div class="mt-2 rounded-lg bg-blue-50 px-2 py-1.5 text-[11px] text-blue-700 dark:bg-blue-950/30 dark:text-blue-300">发现同名知识，将创建 Version {(item.existing_version_no || 0) + 1}。</div>;
    case 'CREATE_DOCUMENT': return <div class="mt-2 rounded-lg bg-emerald-50 px-2 py-1.5 text-[11px] text-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-300">新知识资产，将创建 Document Version 1。</div>;
    default: return null;
  }
}

function EditCell({ value, onInput, disabled, type = 'text' }: { value: string; onInput: (value: string) => void; disabled: boolean; type?: string }) { return <td class="px-2 py-2.5"><input type={type} value={value} disabled={disabled} onInput={event => onInput((event.target as HTMLInputElement).value)} class="w-full min-w-32 rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-xs outline-none focus:border-blue-400 disabled:border-transparent disabled:bg-transparent disabled:px-0 dark:border-slate-700 dark:bg-slate-900"/></td>; }
function Field({ label, value, onInput, type = 'text' }: { label: string; value: string; onInput: (value: string) => void; type?: string }) { return <label class="text-xs font-medium text-slate-500">{label}<input type={type} value={value} onInput={event => onInput((event.target as HTMLInputElement).value)} class="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></label>; }
function MiniStat({ label, value }: { label: string; value: number }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 text-xl font-semibold">{formatNumber(value)}</div></div>; }
function formatBytes(value: number) { if (value < 1024) return `${value} B`; if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`; if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`; return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`; }
