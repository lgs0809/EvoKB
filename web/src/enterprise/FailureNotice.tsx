export function FailureNotice({ title, suggestion, recordId, errorCode, diagnostic, showDiagnostic, waiting = false }: {
  title: string;
  suggestion: string;
  recordId: string;
  errorCode?: string;
  diagnostic?: string;
  showDiagnostic: boolean;
  waiting?: boolean;
}) {
  return <div class={`mt-2 rounded-lg px-2.5 py-2 text-xs leading-5 ${waiting ? 'bg-amber-50 text-amber-800 dark:bg-amber-950/30 dark:text-amber-200' : 'bg-rose-50 text-rose-700 dark:bg-rose-950/30 dark:text-rose-300'}`}>
    <div class="font-semibold">{title}</div>
    <div class="mt-1">{suggestion}</div>
    {showDiagnostic && <details class={`mt-2 border-t pt-2 ${waiting ? 'border-amber-200/70 dark:border-amber-900/70' : 'border-rose-200/70 dark:border-rose-900/70'}`}>
      <summary class="cursor-pointer font-medium">查看管理员诊断</summary>
      <dl class="mt-2 space-y-1 text-[11px]"><div><dt class="inline font-semibold">错误代码：</dt><dd class="inline">{errorCode || '未提供'}</dd></div><div><dt class="inline font-semibold">处理记录：</dt><dd class="inline break-all">{recordId}</dd></div></dl>
      {diagnostic && <pre class="mt-2 max-h-56 overflow-auto whitespace-pre-wrap break-all rounded bg-white/70 p-2 font-mono text-[11px] leading-5 dark:bg-slate-950/60">{diagnostic}</pre>}
    </details>}
  </div>;
}
