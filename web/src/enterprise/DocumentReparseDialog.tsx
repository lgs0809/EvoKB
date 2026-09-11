import { useState } from 'preact/hooks';
import { reparseEnterpriseDocument } from '../lib/api';
import { DialogFrame } from './DialogFrame';
import { PrimaryButton, SecondaryButton } from './ui';

export type DocumentReparseResult = Awaited<ReturnType<typeof reparseEnterpriseDocument>>;

export function reparseResultMessage(result: DocumentReparseResult) {
  return result.reused
    ? '当前解析规则已有处理版本，已复用原任务。原有处理记录保留，尝试次数不会重置。'
    : '重新解析已排队。现有可用版本继续提供查询，新版本处理成功后才会替换；历史失败记录保留。';
}

/** One normal service/dialog for both current documents and retained failed Raw sources. */
export function DocumentReparseDialog({ versionId, logicalName, onClose, onSubmitted, onError }: {
  versionId: string; logicalName: string; onClose: () => void;
  onSubmitted: (result: DocumentReparseResult) => void; onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async () => {
    if (busy) return;
    setBusy(true); setError('');
    try {
      const result = await reparseEnterpriseDocument(versionId);
      onError(''); onSubmitted(result);
    } catch (failure) {
      const message = failure instanceof Error ? failure.message : String(failure);
      setError(message); onError(message);
    } finally { setBusy(false); }
  };
  return <DialogFrame title="重新解析资料" onClose={() => { if (!busy) onClose(); }}>
    <div class="w-full max-w-lg space-y-4 p-5"><p class="text-base font-semibold">{logicalName}</p>
      <p class="text-sm leading-6 text-slate-600 dark:text-slate-300">仅资料所有者或管理员可以操作。将使用当前解析规则处理保留的原始文件。原文件、历史版本与失败记录都会保留；新版本成功发布前，现有可用版本继续提供查询。相同原文与规则会复用原任务。</p>
      {error && <div role="alert" class="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-200">{error}</div>}
      <div class="flex justify-end gap-2"><SecondaryButton disabled={busy} onClick={onClose}>取消</SecondaryButton><PrimaryButton disabled={busy} onClick={() => void submit()}>{busy ? '正在提交…' : '开始重新解析'}</PrimaryButton></div>
    </div>
  </DialogFrame>;
}
