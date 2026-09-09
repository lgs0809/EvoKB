import { DialogFrame } from './DialogFrame';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { createEnterpriseBatch, fetchEnterpriseBatch, retryEnterpriseBatchFile, startEnterpriseBatch, updateEnterpriseBatchFile, type EnterpriseIngestionBatch, type EnterpriseIngestionFile } from '../lib/api';
import { Icon, PrimaryButton, SecondaryButton, StatusBadge, formatNumber } from './ui';
import { IngestionFailure } from './IngestionFailure';
import { uploadOriginal, uploadPolicy, listUploadSessions, cancelUpload, type UploadProgress, type UploadSession } from '../lib/multipartUpload';

type LocalFile = EnterpriseIngestionFile & { tags_text: string };

export function BatchUploadDialog({ onClose, onChanged, onError, initialBatchId, isAdmin = false, securityClearance = 10 }: { onClose: () => void; onChanged: () => void; onError: (message: string) => void; initialBatchId?: string; isAdmin?: boolean; securityClearance?: number }) {
  const [uploadProgress, setUploadProgress] = useState<Record<string, UploadProgress>>({});
  const [uploadedSessions, setUploadedSessions] = useState<UploadSession[]>([]);
  const [activeSessions, setActiveSessions] = useState<UploadSession[]>([]);
  const completedSelections = useRef(new Set<string>());
  const refreshSessions = () => listUploadSessions().then(value => setActiveSessions(value.filter(session => ['UPLOADING', 'COMPLETING'].includes(session.status))));
  const [logicalNames, setLogicalNames] = useState<Record<string, string>>({});
  const [uploadLimit, setUploadLimit] = useState<number | null>(null);
  const uploadAbort = useRef<AbortController | null>(null);
  useEffect(() => { Promise.all([uploadPolicy().then(policy => setUploadLimit(policy.max_file_bytes)), refreshSessions()]).catch(err => onError(String(err instanceof Error ? err.message : err))); return () => uploadAbort.current?.abort(); }, []);
  const [selected, setSelected] = useState<Array<{ file: File; relativePath: string }>>([]);
  const [batch, setBatch] = useState<EnterpriseIngestionBatch | null>(null);
  const [drafts, setDrafts] = useState<Record<string, LocalFile>>({});
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [rejected, setRejected] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [department, setDepartment] = useState('');
  const [documentType, setDocumentType] = useState('');
  const [category, setCategory] = useState('');
  const [documentDate, setDocumentDate] = useState('');
  const [tags, setTags] = useState('');
  const [securityLevel, setSecurityLevel] = useState('');
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
      const archives = selected.filter(item => item.file.name.toLowerCase().endsWith('.zip'));
      const originals = selected.filter(item => !item.file.name.toLowerCase().endsWith('.zip'));
      uploadAbort.current = new AbortController();
      for (const item of originals) {
        const selectionKey = `${item.relativePath}:${item.file.size}:${item.file.lastModified}`;
        if (completedSelections.current.has(selectionKey)) continue;
        const session = await uploadOriginal(item.file, item.relativePath, {
          logical_name: logicalNames[item.relativePath] || item.file.name.replace(/\.[^.]+$/, ''),
          source_department: department, document_type: documentType, material_category: category,
          company: '', document_date: documentDate || null, manual_tags: tags.split(',').map(t => t.trim()).filter(Boolean),
          ...(isAdmin && securityLevel ? { security_level: Number(securityLevel) } : {}),
        }, value => setUploadProgress(prev => ({ ...prev, [item.relativePath]: value })), uploadAbort.current.signal);
        completedSelections.current.add(selectionKey);
        setUploadedSessions(prev => [...prev.filter(old => old.session_id !== session.session_id), session]);
        onChanged();
      }
      if (archives.length) {
        const result = await createEnterpriseBatch(archives, { source_department: department, document_type: documentType, material_category: category, document_date: documentDate, tags,
          ...(isAdmin && securityLevel ? { security_level: securityLevel } : {}) });
        hydrate(result.batch);
        setRejected(result.rejected || []);
      }
      // Every native completed original already has its unique durable batch/outbox.
      // Keep the receipt list visible; upload success is not an assertion of searchable content.

      setDirty(new Set());
      onError('');
    } catch (err) {
      onError(err instanceof DOMException && err.name === 'AbortError' ? '上传已暂停，重新选择同一原件可继续。' : String(err instanceof Error ? err.message : err));
    } finally {
      uploadAbort.current = null;
      void refreshSessions().catch(() => {});
      setBusy(false);
    }
  };

  const cancelSession = async (session: UploadSession) => {
    setBusy(true);
    try { await cancelUpload(session); await refreshSessions(); onError(''); }
    catch (error) { onError(String(error instanceof Error ? error.message : error)); }
    finally { setBusy(false); }
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
          ...(isAdmin ? item.security_level != null ? { security_level: item.security_level } : { clear_security_level: true } : {}),
        });
      }));
    }
    setDirty(new Set());
  };

  const saveAndRefresh = async () => {
    if (!batch || dirty.size === 0) return;
    setBusy(true);
    try {
      await saveDirty();
      hydrate(await fetchEnterpriseBatch(batch.batch_id));
      onChanged();
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
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

  const hasPendingSelection = selected.some(item => item.file.name.toLowerCase().endsWith('.zip') || !completedSelections.current.has(`${item.relativePath}:${item.file.size}:${item.file.lastModified}`));
  const totalSize = useMemo(() => selected.reduce((sum, item) => sum + item.file.size, 0), [selected]);

  return <DialogFrame title="批量添加资料" onClose={onClose}>
    <div class="flex max-h-[94vh] w-full max-w-7xl flex-col overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={event => event.stopPropagation()}>
      <div class="flex items-start justify-between gap-4 border-b border-slate-100 px-5 py-4 dark:border-slate-800 sm:px-6"><div><h3 class="text-xl font-semibold">批量添加资料</h3><p class="mt-1 text-xs text-slate-400">普通原件支持分片直传与续传；确认资料信息后上传，原件校验和解析会在后台进行。ZIP 仍可展开后逐项确认。</p></div><button onClick={onClose} class="rounded-xl p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label="关闭"><Icon name="close"/></button></div>

      <div class="flex-1 overflow-y-auto p-5 sm:p-6">
        {!batch ? <div class="grid gap-5 xl:grid-cols-[1.05fr_.95fr]">
          <div class="space-y-4">
            <div onDragOver={event => event.preventDefault()} onDrop={event => { event.preventDefault(); const files = event.dataTransfer?.files; if (files) addFiles(files); }} class="flex min-h-56 flex-col items-center justify-center rounded-3xl border-2 border-dashed border-slate-300 bg-slate-50 p-6 text-center transition hover:border-blue-400 dark:border-slate-700 dark:bg-slate-950/50">
              <div class="rounded-2xl bg-white p-4 text-blue-600 shadow-sm dark:bg-slate-800"><Icon name="documents" size={28}/></div><div class="mt-4 text-base font-semibold">拖拽文件或 ZIP 到这里</div><div class="mt-1 max-w-md text-xs leading-5 text-slate-400">也可以选择多个文件或整个文件夹。{uploadLimit !== null && <span> 单个原件上限 {Math.floor(uploadLimit / 1024 / 1024)} MiB。</span>}</div>
              <div class="mt-5 flex flex-wrap justify-center gap-2"><SecondaryButton onClick={() => fileInput.current?.click()} icon="file">选择多个文件</SecondaryButton><SecondaryButton onClick={() => directoryInput.current?.click()} icon="documents">选择文件夹</SecondaryButton></div>
              <input ref={fileInput} type="file" multiple class="hidden" onChange={event => { if ((event.target as HTMLInputElement).files) addFiles((event.target as HTMLInputElement).files!); }}/>
              <input ref={directoryInput} type="file" multiple class="hidden" {...({ webkitdirectory: '', directory: '' } as Record<string, string>)} onChange={event => { if ((event.target as HTMLInputElement).files) addFiles((event.target as HTMLInputElement).files!); }}/>
            </div>
            {selected.length > 0 && <div class="rounded-2xl border border-slate-200 dark:border-slate-800"><div class="flex items-center justify-between border-b border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800"><span>已选择 {selected.length} 个输入 · {formatBytes(totalSize)}</span><button disabled={busy} onClick={() => setSelected([])} class="font-semibold text-rose-500">清空</button></div><div class="max-h-64 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">{selected.map((item, index) => <div key={`${item.relativePath}-${index}`} class="flex flex-col items-stretch gap-2 px-4 py-2.5 text-xs sm:flex-row sm:items-center sm:gap-3"><Icon name="file" size={15} class="shrink-0 text-slate-400"/><div class="min-w-0 flex-1 truncate text-slate-600 dark:text-slate-300">{item.relativePath}</div><div class="w-full shrink-0 sm:w-56"><input aria-label={`知识名称 ${item.file.name}`} value={logicalNames[item.relativePath] || item.file.name.replace(/\.[^.]+$/, '')} disabled={busy} onInput={event => setLogicalNames(prev => ({ ...prev, [item.relativePath]: (event.target as HTMLInputElement).value }))} class="w-full rounded-lg border border-slate-200 px-2 py-1 dark:border-slate-700 dark:bg-slate-900"/><div class="mt-1 text-[11px] text-slate-400">{uploadProgress[item.relativePath] ? `${uploadProgress[item.relativePath].stage} · ${Math.round(uploadProgress[item.relativePath].bytes / uploadProgress[item.relativePath].total * 100)}%` : formatBytes(item.file.size)}</div></div></div>)}</div></div>}
          </div>
          <div class="rounded-2xl border border-slate-200 p-5 dark:border-slate-800"><div class="text-sm font-semibold">默认资料信息</div><div class="mt-1 text-xs leading-5 text-slate-400">普通原件会按这些信息进入后台入库；可先在文件列表中调整知识名称。</div><div class="mt-5 grid gap-3 sm:grid-cols-2"><Field disabled={busy} label="来源部门" value={department} onInput={setDepartment}/><Field disabled={busy} label="文档类型" value={documentType} onInput={setDocumentType}/><Field disabled={busy} label="材料分类" value={category} onInput={setCategory}/><Field disabled={busy} label="文档日期" value={documentDate} onInput={setDocumentDate} type="date"/><Field disabled={busy} label="标签（逗号分隔）" value={tags} onInput={setTags}/>{isAdmin && <ClassificationField value={securityLevel} disabled={busy} securityClearance={securityClearance} onChange={setSecurityLevel}/>}</div><p class="mt-3 text-xs leading-5 text-slate-400">访问范围在入库前生效。未指定时沿用原资料范围，新资料默认为公开；仅管理员可以指定范围。</p></div>
          {activeSessions.length > 0 && <div class="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-xs text-amber-900 xl:col-span-2 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200"><div class="font-semibold">未结束的上传</div><div class="mt-1 leading-5">重选同一原件并使用相同资料信息即可继续。已上传分片会自动核对；会话创建 24 小时后失效。</div><div class="mt-3 space-y-2">{activeSessions.map(session => <div key={session.session_id} class="flex items-center justify-between gap-3"><div class="min-w-0"><div class="truncate">{session.filename} · {session.metadata.logical_name}</div><div class="mt-1 text-[11px] opacity-75">{session.status === 'COMPLETING' ? '正在合并和对账' : '等待继续上传'} · 截止 {new Date(session.expires_at).toLocaleString()}</div></div>{session.status === 'UPLOADING' && <SecondaryButton disabled={busy} onClick={() => void cancelSession(session)}>取消此上传</SecondaryButton>}</div>)}</div></div>}
          {uploadedSessions.length > 0 && <div class="rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-xs text-emerald-800 xl:col-span-2 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-300"><div class="font-semibold">原件已保存，后台正在校验和入库</div><div class="mt-2 space-y-1">{uploadedSessions.map(session => <div key={session.session_id}>{session.filename} · 入库记录 {session.batch_id.slice(0, 12)}</div>)}</div><div class="mt-2">关闭后可在入库记录查看解析与索引的实际结果。</div></div>}
        </div> : <BatchEditor batch={batch} isAdmin={isAdmin} securityClearance={securityClearance} drafts={drafts} dirty={dirty} rejected={rejected} onPatch={patchDraft} onRetry={retry}/>}
      </div>

      <div class="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 bg-slate-50 px-5 py-4 dark:border-slate-800 dark:bg-slate-950/50 sm:px-6"><div class="text-xs text-slate-400">{batch ? <>批次 {batch.batch_id.slice(0, 12)} · <StatusBadge value={batch.status}/></> : `已选择 ${selected.length} 个文件`}</div><div class="flex flex-wrap gap-2"><SecondaryButton onClick={onClose}>{terminal ? '完成' : '稍后查看'}</SecondaryButton>{!batch ? <>{busy && <SecondaryButton onClick={() => uploadAbort.current?.abort()}>暂停上传</SecondaryButton>}<PrimaryButton onClick={() => void stage()} disabled={busy || !hasPendingSelection} icon="documents">{busy ? '正在上传…' : selected.some(item => item.file.name.toLowerCase().endsWith('.zip')) ? '确认并上传' : '确认资料信息并上传'}</PrimaryButton></> : batch.status === 'STAGED' ? <>{dirty.size > 0 && <SecondaryButton onClick={() => void saveAndRefresh()} disabled={busy} icon="check">{busy ? '保存中…' : `保存 ${dirty.size} 项`}</SecondaryButton>}<PrimaryButton onClick={() => void start()} disabled={busy || dirty.size > 0} icon="sparkles">{dirty.size > 0 ? '先保存资料信息' : busy ? '启动中…' : '开始入库'}</PrimaryButton></> : null}</div></div>
    </div>
  </DialogFrame>;
}

function BatchEditor({ batch, isAdmin, securityClearance, drafts, dirty, rejected, onPatch, onRetry }: { batch: EnterpriseIngestionBatch; isAdmin: boolean; securityClearance: number; drafts: Record<string, LocalFile>; dirty: Set<string>; rejected: string[]; onPatch: (fileId: string, patch: Partial<LocalFile>) => void; onRetry: (item: LocalFile) => void }) {
  const files = batch.files || [];
  const editable = batch.status === 'STAGED';
  return <div class="space-y-4">
    <div class="grid gap-3 sm:grid-cols-3 lg:grid-cols-6"><MiniStat label="文件" value={batch.file_count}/><MiniStat label="待处理" value={batch.staged_count + batch.queued_count}/><MiniStat label="运行" value={batch.running_count}/><MiniStat label="完成" value={batch.succeeded_count}/><MiniStat label="失败" value={batch.failed_count}/><MiniStat label="已修改属性" value={dirty.size}/></div>
    {rejected.length > 0 && <details class="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-300"><summary class="cursor-pointer font-semibold">{rejected.length} 个条目未进入批次</summary><ul class="mt-2 space-y-1">{rejected.map((item, i) => <li key={i}>{item}</li>)}</ul></details>}
    <div class="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-800"><table class="min-w-[1320px] w-full text-xs"><thead class="sticky top-0 bg-slate-50 text-left text-[10px] font-semibold tracking-wide text-slate-400 dark:bg-slate-950"><tr><th class="px-3 py-3">文件 / 进度</th><th class="px-3 py-3">知识名称</th><th class="px-3 py-3">部门</th><th class="px-3 py-3">类型</th><th class="px-3 py-3">分类</th><th class="px-3 py-3">日期</th><th class="px-3 py-3">标签</th><th class="px-3 py-3">访问范围</th><th class="px-3 py-3">操作</th></tr></thead><tbody class="divide-y divide-slate-100 dark:divide-slate-800">{files.map(raw => { const item = drafts[raw.file_id] || { ...raw, tags_text: (raw.manual_tags || []).join(', ') }; return <tr key={item.file_id} class="align-top"><td class="max-w-xs px-3 py-3"><div class="flex items-center gap-2"><StatusBadge value={item.status}/><span class="font-semibold text-slate-600 dark:text-slate-300">{item.progress_stage}</span></div><div class="mt-2 break-all text-slate-500">{item.relative_path}</div><BehaviorHint item={item} dirty={dirty.has(item.file_id)} onUseExisting={() => item.duplicate_logical_name && onPatch(item.file_id, { logical_name: item.duplicate_logical_name })}/><IngestionFailure file={item} isAdmin={isAdmin} /></td><EditCell value={item.logical_name} disabled={!editable} onInput={value => onPatch(item.file_id,{ logical_name:value })}/><EditCell value={item.source_department || ''} disabled={!editable} onInput={value => onPatch(item.file_id,{ source_department:value })}/><EditCell value={item.document_type || ''} disabled={!editable} onInput={value => onPatch(item.file_id,{ document_type:value })}/><EditCell value={item.material_category || ''} disabled={!editable} onInput={value => onPatch(item.file_id,{ material_category:value })}/><EditCell value={item.document_date?.slice(0,10) || ''} type="date" disabled={!editable} onInput={value => onPatch(item.file_id,{ document_date:value })}/><EditCell value={item.tags_text} disabled={!editable} onInput={value => onPatch(item.file_id,{ tags_text:value })}/><td class="min-w-44 px-3 py-3">{editable && isAdmin ? <ClassificationField value={item.security_level == null ? '' : String(item.security_level)} disabled={false} securityClearance={securityClearance} onChange={value => onPatch(item.file_id, { security_level: value ? Number(value) : null })}/> : <span>{classificationLabel(item.security_level)}</span>}</td><td class="px-3 py-3">{item.status === 'FAILED' ? <button onClick={() => void onRetry(item)} class="rounded-lg border border-rose-200 px-2.5 py-1.5 font-semibold text-rose-600 hover:bg-rose-50 dark:border-rose-900">重试</button> : dirty.has(item.file_id) ? <span class="text-blue-500">待保存</span> : <span class="text-slate-300">—</span>}</td></tr>; })}</tbody></table></div>
  </div>;
}

function BehaviorHint({ item, dirty, onUseExisting }: { item: LocalFile; dirty: boolean; onUseExisting: () => void }) {
  if (dirty) return <div class="mt-2 text-[11px] font-medium text-blue-500">资料名称已修改，保存后会重新检查版本和重复内容。</div>;
  switch (item.expected_behavior) {
    case 'REUSE_VERSION': return <div class="mt-2 rounded-lg bg-slate-100 px-2 py-1.5 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300">内容与现有版本一致，将复用已有版本。</div>;
    case 'DUPLICATE_CONTENT': return <div class="mt-2 rounded-lg bg-amber-50 px-2 py-1.5 text-[11px] text-amber-700 dark:bg-amber-950/30 dark:text-amber-300"><div>相同内容已存在于「{item.duplicate_logical_name || '其他资料'}」，仍可继续保存为当前资料。</div>{item.duplicate_logical_name && <button type="button" onClick={onUseExisting} class="mt-2 rounded-md bg-amber-600 px-2 py-1 font-semibold text-white">沿用现有资料名</button>}</div>;
    case 'CREATE_VERSION': return <div class="mt-2 rounded-lg bg-blue-50 px-2 py-1.5 text-[11px] text-blue-700 dark:bg-blue-950/30 dark:text-blue-300">发现同名资料，将创建版本 {(item.existing_version_no || 0) + 1}。</div>;
    case 'CREATE_DOCUMENT': return <div class="mt-2 rounded-lg bg-emerald-50 px-2 py-1.5 text-[11px] text-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-300">新资料，将创建版本 1。</div>;
    default: return null;
  }
}

function EditCell({ value, onInput, disabled, type = 'text' }: { value: string; onInput: (value: string) => void; disabled: boolean; type?: string }) { return <td class="px-2 py-2.5"><input type={type} value={value} disabled={disabled} onInput={event => onInput((event.target as HTMLInputElement).value)} class="w-full min-w-32 rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-xs outline-none focus:border-blue-400 disabled:border-transparent disabled:bg-transparent disabled:px-0 dark:border-slate-700 dark:bg-slate-900"/></td>; }
function Field({ label, value, onInput, type = 'text', disabled = false }: { label: string; value: string; onInput: (value: string) => void; type?: string; disabled?: boolean }) { return <label class="text-xs font-medium text-slate-500">{label}<input type={type} value={value} disabled={disabled} onInput={event => onInput((event.target as HTMLInputElement).value)} class="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"/></label>; }
function MiniStat({ label, value }: { label: string; value: number }) { return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 text-xl font-semibold">{formatNumber(value)}</div></div>; }
function formatBytes(value: number) { if (value < 1024) return `${value} B`; if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`; if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`; return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`; }

function classificationLabel(value?: number | null) {
  return value == null ? '沿用原资料（新资料公开）' : ({10: '公开资料', 20: '内部资料', 30: '保密资料', 40: '严格保密资料'} as Record<number, string>)[value] || `安全等级 ${value}`;
}
function ClassificationField({ value, disabled, securityClearance, onChange }: { value: string; disabled: boolean; securityClearance: number; onChange: (value: string) => void }) {
  return <label class="text-xs font-medium text-slate-500">访问范围<select aria-label="资料访问范围" value={value} disabled={disabled} onChange={event => onChange((event.target as HTMLSelectElement).value)} class="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm dark:border-slate-700 dark:bg-slate-950"><option value="">沿用原资料（新资料公开）</option>{[10, 20, 30, 40].map(level => <option key={level} value={level} disabled={level > securityClearance}>{classificationLabel(level)}{level > securityClearance ? '（超出当前权限）' : ''}</option>)}{value && !['10', '20', '30', '40'].includes(value) && <option value={value} disabled={Number(value) > securityClearance}>{classificationLabel(Number(value))}{Number(value) > securityClearance ? '（超出当前权限）' : ''}</option>}</select></label>;
}
