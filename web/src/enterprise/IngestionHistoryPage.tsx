import { useEffect, useState } from 'preact/hooks';
import {
  fetchEnterpriseBatch,
  fetchEnterpriseBatches,
  retryEnterpriseBatchFile,
  type EnterpriseIngestionBatch,
  type EnterpriseIngestionFile,
} from '../lib/api';
import { BatchUploadDialog } from './BatchUploadDialog';
import { EmptyState, Icon, LoadingBlock, PrimaryButton, SectionTitle, StatusBadge, formatDate } from './ui';
import type { EnterpriseSection } from './EnterpriseShell';

export function IngestionHistoryPage({ isAdmin, canUpload, onNavigate, onError }: {
  isAdmin: boolean;
  canUpload: boolean;
  onNavigate: (section: EnterpriseSection) => void;
  onError: (message: string) => void;
}) {
  const [batches, setBatches] = useState<EnterpriseIngestionBatch[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState('ALL');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<EnterpriseIngestionBatch | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [retrying, setRetrying] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [resumeBatchId, setResumeBatchId] = useState('');
  const pageSize = 20;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  const load = async (nextPage = page, nextStatus = status) => {
    setLoading(true);
    try {
      const data = await fetchEnterpriseBatches({ page: nextPage, page_size: pageSize, q: query.trim() || undefined, status: nextStatus });
      setBatches(data.batches || []);
      setTotal(data.total || 0);
      setPage(nextPage);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setLoading(false);
    }
  };

  const openBatch = async (batch: EnterpriseIngestionBatch) => {
    setSelected(batch);
    setDetailLoading(true);
    try {
      setSelected(await fetchEnterpriseBatch(batch.batch_id));
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setDetailLoading(false);
    }
  };

  const retry = async (file: EnterpriseIngestionFile) => {
    setRetrying(file.file_id);
    try {
      await retryEnterpriseBatchFile(file.file_id);
      if (selected) setSelected(await fetchEnterpriseBatch(selected.batch_id));
      await load(page, status);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setRetrying('');
    }
  };

  useEffect(() => { void load(1, 'ALL'); }, []);

  return <div class="space-y-6">
    <section class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <SectionTitle title={isAdmin ? '入库记录' : '上传记录'} action={canUpload ? <PrimaryButton onClick={() => setUploadOpen(true)} icon="upload">上传资料</PrimaryButton> : undefined} />
      <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div class="relative min-w-0 flex-1 lg:max-w-xl">
          <Icon name="search" size={16} class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input value={query} onInput={event => setQuery((event.target as HTMLInputElement).value)} onKeyDown={event => { if (event.key === 'Enter') void load(1); }} placeholder="搜索批次或文件" class="w-full rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-3 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950" />
        </div>
        <div class="flex gap-2 overflow-x-auto">{['ALL', 'STAGED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].map(item => <button key={item} onClick={() => { setStatus(item); void load(1, item); }} class={`whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold transition ${status === item ? 'bg-blue-600 text-white shadow-sm shadow-blue-600/15 dark:bg-blue-500' : 'bg-slate-100 text-slate-500 hover:bg-blue-50 hover:text-blue-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-blue-950/30 dark:hover:text-blue-300'}`}>{batchStatusLabel(item)}</button>)}</div>
      </div>
    </section>

    <section class="grid gap-5 xl:grid-cols-[minmax(0,1.15fr)_minmax(380px,.85fr)]">
      <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {loading ? <div class="p-5"><LoadingBlock rows={7} /></div> : batches.length === 0 ? <div class="p-5"><EmptyState icon="documents" title="暂无记录" description={isAdmin ? '新的资料入库会显示在这里。' : '你上传的资料会显示在这里。'} /></div> : <div class="divide-y divide-slate-100 dark:divide-slate-800">{batches.map(batch => <button key={batch.batch_id} onClick={() => void openBatch(batch)} class={`w-full px-4 py-4 text-left transition sm:px-5 ${selected?.batch_id === batch.batch_id ? 'bg-blue-50/70 dark:bg-blue-950/20' : 'hover:bg-slate-50 dark:hover:bg-slate-950/30'}`}>
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2"><StatusBadge value={displayBatchStatus(batch)} />{isAdmin && batch.created_by_username && <span class="text-xs font-medium text-slate-500 dark:text-slate-300">{batch.created_by_username}</span>}</div>
              <div class="mt-2 text-sm font-semibold text-slate-800 dark:text-slate-100">{batch.file_count} 个文件</div>
              <div class="mt-1 text-xs text-slate-400">完成 {batch.succeeded_count} · 排队中 {batch.queued_count} · 处理中 {batch.running_count} · 失败 {batch.failed_count}</div>
            </div>
            <div class="text-right text-xs text-slate-400"><div>{formatDate(batch.created_at)}</div><div class="mt-2 font-medium text-blue-600 dark:text-blue-400">查看详情</div></div>
          </div>
        </button>)}</div>}
        <Pager page={page} pageCount={pageCount} total={total} onPage={next => void load(next)} />
      </div>

      <aside class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
        {!selected ? <EmptyState icon="documents" title="选择一条记录" description="查看这次入库中的文件和处理状态。" /> : detailLoading ? <LoadingBlock rows={6} /> : <BatchDetail batch={selected} isAdmin={isAdmin} retrying={retrying} onRetry={retry} onNavigate={onNavigate} onResume={() => setResumeBatchId(selected.batch_id)} />}
      </aside>
    </section>
    {uploadOpen && <BatchUploadDialog onClose={() => setUploadOpen(false)} onChanged={() => { setUploadOpen(false); void load(1, 'ALL'); }} onError={onError} />}
    {resumeBatchId && <BatchUploadDialog initialBatchId={resumeBatchId} onClose={() => setResumeBatchId('')} onChanged={() => { setResumeBatchId(''); void load(page, status); if (selected) void openBatch(selected); }} onError={onError} />}
  </div>;
}

function BatchDetail({ batch, isAdmin, retrying, onRetry, onNavigate, onResume }: {
  batch: EnterpriseIngestionBatch;
  isAdmin: boolean;
  retrying: string;
  onRetry: (file: EnterpriseIngestionFile) => Promise<void>;
  onNavigate: (section: EnterpriseSection) => void;
  onResume: () => void;
}) {
  return <div class="space-y-5">
    <div class="flex items-start justify-between gap-3"><div><StatusBadge value={displayBatchStatus(batch)} /><h3 class="mt-3 text-lg font-semibold">{batch.file_count} 个文件</h3><div class="mt-1 text-xs text-slate-400">{formatDate(batch.created_at)}{isAdmin && batch.created_by_username ? ` · ${batch.created_by_username}` : ''}</div></div><div class="flex flex-wrap justify-end gap-2">{batch.status === 'STAGED' && <button onClick={onResume} class="rounded-xl bg-blue-600 px-3 py-2 text-xs font-semibold text-white shadow-sm shadow-blue-600/15 transition hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400">继续配置</button>}<button onClick={() => onNavigate('documents')} class="rounded-xl border border-blue-200 bg-blue-50/70 px-3 py-2 text-xs font-semibold text-blue-700 transition hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300">资料管理</button></div></div>
    <div class="grid grid-cols-2 gap-2 sm:grid-cols-4"><MiniStat label="已完成" value={batch.succeeded_count} /><MiniStat label="排队中" value={batch.queued_count} /><MiniStat label="处理中" value={batch.running_count} /><MiniStat label="失败" value={batch.failed_count} /></div>
    <div class="max-h-[58vh] space-y-2 overflow-y-auto pr-1">{(batch.files || []).map(file => <div key={file.file_id} class="rounded-xl border border-slate-100 p-3 dark:border-slate-800">
      <div class="flex items-start justify-between gap-3"><div class="min-w-0 flex-1"><div class="truncate text-sm font-semibold text-slate-700 dark:text-slate-200">{file.logical_name || file.original_filename}</div><div class="mt-1 truncate text-xs text-slate-400">{file.original_filename}</div></div><StatusBadge value={file.status} /></div>
      <div class="mt-2 text-xs font-medium text-blue-600 dark:text-blue-400">{ingestionStageLabel(file.progress_stage || file.status)}</div>
      {file.error_message && <div class="mt-2 rounded-lg bg-rose-50 px-2.5 py-2 text-xs leading-5 text-rose-600 dark:bg-rose-950/30 dark:text-rose-300">{file.error_message}</div>}
      <div class="mt-3 flex items-center justify-between gap-2"><span class="text-[11px] text-slate-400">{file.version_id ? '已进入资料管理' : `第 ${file.attempt} 次处理`}</span>{file.status === 'FAILED' && <button onClick={() => void onRetry(file)} disabled={retrying === file.file_id} class="rounded-lg border border-rose-200 px-2.5 py-1.5 text-xs font-semibold text-rose-600 hover:bg-rose-50 disabled:opacity-50 dark:border-rose-900">{retrying === file.file_id ? '排队中…' : '重新处理'}</button>}</div>
    </div>)}{(!batch.files || batch.files.length === 0) && <div class="py-8 text-center text-sm text-slate-400">暂无文件明细</div>}</div>
  </div>;
}

function displayBatchStatus(batch: EnterpriseIngestionBatch) {
  if (batch.running_count > 0) return 'RUNNING';
  if (batch.queued_count > 0) return 'QUEUED';
  if (batch.staged_count > 0) return 'STAGED';
  if (batch.succeeded_count === batch.file_count && batch.file_count > 0) return 'SUCCEEDED';
  if (batch.failed_count === batch.file_count && batch.file_count > 0) return 'FAILED';
  if (batch.failed_count > 0 && batch.succeeded_count > 0) return 'PARTIAL';
  return batch.status;
}

function batchStatusLabel(value: string) {
  const labels: Record<string, string> = { ALL: '全部', STAGED: '待确认', QUEUED: '排队中', PENDING: '等待处理', RUNNING: '处理中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' };
  return labels[value] || value;
}

function ingestionStageLabel(value: string) {
  const labels: Record<string, string> = {
    STAGED: '待确认', QUEUED: '排队中', PENDING: '等待处理', RUNNING: '处理中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
    STORING: '保存文件', PARSING: '解析资料', EVIDENCE: '整理内容', EMBEDDING: '建立检索', INDEXING: '建立检索', ACTIVATING: '准备完成',
  };
  return labels[(value || '').toUpperCase()] || value || '—';
}

function MiniStat({ label, value }: { label: string; value: number }) { return <div class="rounded-xl bg-slate-50 p-3 text-center dark:bg-slate-950/60"><div class="text-lg font-semibold">{value}</div><div class="mt-0.5 text-[10px] font-medium text-slate-400">{label}</div></div>; }
function Pager({ page, pageCount, total, onPage }: { page: number; pageCount: number; total: number; onPage: (page: number) => void }) { return <div class="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-400 dark:border-slate-800"><span>共 {total} 条 · 第 {page}/{pageCount} 页</span><div class="flex gap-2"><button disabled={page <= 1} onClick={() => onPage(page - 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">上一页</button><button disabled={page >= pageCount} onClick={() => onPage(page + 1)} class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40 dark:border-slate-700">下一页</button></div></div>; }
