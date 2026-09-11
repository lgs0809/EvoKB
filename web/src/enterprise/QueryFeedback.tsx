import { useEffect, useRef, useState } from 'preact/hooks';
import { submitEnterpriseQueryFeedback } from '../lib/api';

const CATEGORIES = [
  ['MISSING_NEEDED_MATERIAL', '没找到需要的资料'],
  ['IRRELEVANT_MATERIAL', '找到的资料不相关'],
  ['ANSWER_INCORRECT', '资料正确但回答有误'],
  ['ANSWER_INCOMPLETE', '回答不完整'],
  ['CITATION_PROBLEM', '引用原文有问题'],
  ['SOURCE_CONFLICT', '企业资料本身冲突'],
] as const;

/** Keyed by answer identity so a late feedback response cannot alter another answer. */
export function QueryFeedback({ sessionId, onError }: { sessionId: string; onError: (message: string) => void }) {
  const [rating, setRating] = useState<'POSITIVE' | 'NEGATIVE' | null>(null);
  const [menu, setMenu] = useState(false);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  const submit = async (next: 'POSITIVE' | 'NEGATIVE', category = '') => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      await submitEnterpriseQueryFeedback(sessionId, next, category);
      if (!mounted.current) return;
      setRating(next);
      setMenu(false);
      onError('');
    } catch (error) {
      if (mounted.current) onError(String(error instanceof Error ? error.message : error));
    } finally {
      pending.current = false;
      if (mounted.current) setBusy(false);
    }
  };
  const style = (selected: boolean) => `rounded-lg border px-2.5 py-1.5 disabled:cursor-wait disabled:opacity-60 ${selected
    ? 'border-blue-400 bg-blue-50 text-blue-700 dark:bg-blue-950/30'
    : 'border-slate-200 hover:border-blue-300 dark:border-slate-700'}`;
  return <div class="mt-5 border-t border-slate-200 pt-4 text-xs text-slate-500 dark:border-slate-800">
    <div class="flex flex-wrap items-center gap-2">
      <span>这次查询是否解决了你的问题？</span>
      <button disabled={busy} aria-pressed={rating === 'POSITIVE'} class={style(rating === 'POSITIVE')}
        onClick={() => void submit('POSITIVE')}>已解决</button>
      <button disabled={busy} aria-pressed={rating === 'NEGATIVE'} aria-expanded={menu} class={style(rating === 'NEGATIVE')}
        onClick={() => setMenu(value => !value)}>需改进</button>
    </div>
    <div role="status" aria-live="polite" class="mt-2 min-h-4">
      {busy ? '正在保存反馈…' : rating ? '反馈已保存，谢谢。' : ''}
    </div>
    {menu && <div class="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
      {CATEGORIES.map(([category, label]) => <button key={category} disabled={busy}
        onClick={() => void submit('NEGATIVE', category)}
        class="rounded-lg border border-slate-200 bg-white px-3 py-2 text-left text-xs text-slate-600 transition hover:border-blue-300 hover:text-blue-700 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">{label}</button>)}
    </div>}
  </div>;
}
