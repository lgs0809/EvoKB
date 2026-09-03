import { useEffect, useMemo, useState } from 'preact/hooks';
import {
  continueEnterpriseQuery,
  fetchEnterpriseFacets,
  fetchEnterpriseQuerySession,
  fetchEnterpriseQuerySessions,
  packageEnterpriseSources,
  queryEnterpriseKnowledge,
  submitEnterpriseQueryFeedback,
  type EnterpriseEvidence,
  type EnterpriseFacets,
  type EnterpriseQueryResponse,
  type EnterpriseQuerySessionSummary,
  type EnterpriseSearchFilters,
} from '../lib/api';
import { KnowledgeScope } from './KnowledgeScope';
import { EmptyState, Icon, SecondaryButton, SectionTitle } from './ui';

const SESSION_KEY = 'enterprise-kb-active-query-session';
const NEGATIVE_FEEDBACK = [
  ['MISSING_NEEDED_MATERIAL', '没找到需要的资料'],
  ['IRRELEVANT_MATERIAL', '找到的资料不相关'],
  ['ANSWER_INCORRECT', '资料正确但回答有误'],
  ['ANSWER_INCOMPLETE', '回答不完整'],
  ['CITATION_PROBLEM', '引用原文有问题'],
  ['SOURCE_CONFLICT', '企业资料本身冲突'],
] as const;

export function AskPage({
  onOpenEvidence,
  onError,
}: {
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [question, setQuestion] = useState('');
  const [clarification, setClarification] = useState('');
  const [result, setResult] = useState<EnterpriseQueryResponse | null>(null);
  const [history, setHistory] = useState<EnterpriseQuerySessionSummary[]>([]);
  const [facets, setFacets] = useState<EnterpriseFacets>({
    companies: [],
    source_departments: [],
    document_types: [],
    material_categories: [],
  });
  const [filters, setFilters] = useState<EnterpriseSearchFilters>({});
  const [busy, setBusy] = useState(false);
  const [busyStage, setBusyStage] = useState('正在查找资料…');
  const [packageBusy, setPackageBusy] = useState(false);
  const [feedback, setFeedback] = useState<'POSITIVE' | 'NEGATIVE' | null>(null);
  const [feedbackMenu, setFeedbackMenu] = useState(false);

  const loadHistory = async () => {
    try {
      const data = await fetchEnterpriseQuerySessions(12);
      setHistory(data.sessions || []);
    } catch {
      // History is a secondary surface; the query page remains usable if it cannot be loaded.
    }
  };

  useEffect(() => {
    fetchEnterpriseFacets().then(setFacets).catch(() => undefined);
    void loadHistory();
    const sessionId = localStorage.getItem(SESSION_KEY);
    if (!sessionId) return;
    fetchEnterpriseQuerySession(sessionId)
      .then(setResult)
      .catch(() => localStorage.removeItem(SESSION_KEY));
  }, []);

  useEffect(() => {
    if (!busy) {
      setBusyStage('正在查找资料…');
      return;
    }
    setBusyStage('正在查找资料…');
    const supplement = window.setTimeout(() => setBusyStage('正在补充相关信息…'), 8000);
    const verify = window.setTimeout(() => setBusyStage('正在核对原文依据…'), 24000);
    return () => {
      window.clearTimeout(supplement);
      window.clearTimeout(verify);
    };
  }, [busy]);

  useEffect(() => {
    if (!result || result.status !== 'RUNNING') return;
    setBusy(true);
    let cancelled = false;
    const poll = async () => {
      try {
        const current = await fetchEnterpriseQuerySession(result.session_id);
        if (cancelled) return;
        setResult(current);
        if (current.status !== 'RUNNING') {
          setBusy(false);
          void loadHistory();
        }
      } catch (err) {
        if (!cancelled) {
          setBusy(false);
          onError(String(err instanceof Error ? err.message : err));
        }
      }
    };
    const timer = window.setInterval(() => void poll(), 1500);
    void poll();
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [result?.session_id, result?.status]);

  const evidenceIds = useMemo(
    () =>
      Array.from(
        new Set((result?.citations || []).map(item => item.evidence.evidence_id).filter(Boolean)),
      ),
    [result],
  );

  const rememberSession = (response: EnterpriseQueryResponse) => {
    setResult(response);
    setFeedback(null);
    setFeedbackMenu(false);
    localStorage.setItem(SESSION_KEY, response.session_id);
  };

  const ask = async (value = question) => {
    const next = value.trim();
    if (!next) return;
    setQuestion(next);
    setClarification('');
    setResult(null);
    setBusy(true);
    try {
      const response = await queryEnterpriseKnowledge(next, filters, 8);
      rememberSession(response);
      void loadHistory();
      if (response.status !== 'RUNNING') setBusy(false);
      onError('');
    } catch (err) {
      setBusy(false);
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  const openHistory = async (item: EnterpriseQuerySessionSummary) => {
    setQuestion(item.question);
    setBusy(item.status === 'RUNNING');
    try {
      const response = await fetchEnterpriseQuerySession(item.session_id);
      rememberSession(response);
      onError('');
    } catch (err) {
      setBusy(false);
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  const continueQuery = async (value = clarification) => {
    if (!result || result.status !== 'WAITING_USER') return;
    const next = value.trim();
    if (!next) return;
    setClarification(next);
    setBusy(true);
    try {
      const response = await continueEnterpriseQuery(result.session_id, next, 8);
      rememberSession(response);
      setClarification('');
      void loadHistory();
      if (response.status !== 'RUNNING') setBusy(false);
      onError('');
    } catch (err) {
      setBusy(false);
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  const downloadSources = async () => {
    if (!evidenceIds.length) return;
    setPackageBusy(true);
    try {
      const published = await packageEnterpriseSources(evidenceIds);
      window.open(published.download_url, '_blank', 'noopener,noreferrer');
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setPackageBusy(false);
    }
  };

  const sendFeedback = async (
    rating: 'POSITIVE' | 'NEGATIVE',
    category = '',
  ) => {
    if (!result) return;
    try {
      await submitEnterpriseQueryFeedback(result.session_id, rating, category);
      setFeedback(rating);
      setFeedbackMenu(false);
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    }
  };

  return (
    <div class="space-y-6">
      <section class="overflow-hidden rounded-3xl border border-blue-100 bg-white shadow-sm dark:border-blue-950/70 dark:bg-slate-900">
        <div class="border-b border-blue-100 bg-gradient-to-br from-blue-50 via-white to-sky-50 px-5 py-8 text-slate-900 dark:border-blue-950/70 dark:from-blue-950/50 dark:via-slate-900 dark:to-slate-900 dark:text-slate-100 sm:px-8 sm:py-10">
          <div class="mx-auto max-w-4xl">
            <h2 class="text-2xl font-semibold tracking-tight sm:text-3xl">想了解什么？</h2>
            <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
              输入问题即可查询企业知识；复杂问题会自动补充查找相关资料。
            </p>
            <div class="mt-6 rounded-2xl border border-blue-200 bg-white p-2 shadow-lg shadow-blue-100/40 dark:border-blue-900/70 dark:bg-slate-950/60 dark:shadow-none">
              <textarea
                value={question}
                onInput={event => setQuestion((event.target as HTMLTextAreaElement).value)}
                onKeyDown={event => {
                  if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void ask();
                }}
                placeholder="例如：过去三年公司的主要经营风险发生了哪些变化？"
                rows={3}
                class="w-full resize-none bg-transparent px-3 py-2 text-base text-slate-800 outline-none placeholder:text-slate-400 dark:text-slate-100"
              />
              <div class="flex flex-wrap items-center justify-end gap-3 border-t border-blue-100 px-2 pt-2 dark:border-blue-950/70">
                <button
                  onClick={() => void ask()}
                  disabled={busy || !question.trim()}
                  class="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm shadow-blue-600/15 transition hover:bg-blue-700 disabled:opacity-50 dark:bg-blue-500 dark:hover:bg-blue-400"
                >
                  {busy ? '查询中…' : '查询'}
                </button>
              </div>
            </div>
            <div class="mt-4">
              <KnowledgeScope facets={facets} filters={filters} onChange={setFilters} />
            </div>
          </div>
        </div>

        <div class="p-5 sm:p-8">
          {!result && !busy && (
            <div class="mx-auto max-w-4xl">
              {history.length > 0 ? (
                <div>
                  <SectionTitle title="最近查询" description="查询记录保存在当前账号中，可重新打开继续查看。" />
                  <div class="mt-4 grid gap-2 sm:grid-cols-2">
                    {history.map(item => (
                      <button
                        key={item.session_id}
                        onClick={() => void openHistory(item)}
                        class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-3 text-left transition hover:border-blue-300 dark:border-slate-700 dark:bg-slate-950"
                      >
                        <div class="line-clamp-2 text-sm font-medium text-slate-700 dark:text-slate-200">{item.question}</div>
                        <div class="mt-2 flex items-center justify-between gap-2 text-[11px] text-slate-400">
                          <span>{historyStatus(item.status)}</span>
                          <span>{formatHistoryTime(item.updated_at)}</span>
                        </div>
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <EmptyState
                  icon="search"
                  title="输入问题开始查询"
                  description="系统会按需补充查找，并在最终回答中附上可核对的原文依据。"
                />
              )}
            </div>
          )}

          {busy && (
            <div class="mx-auto flex min-h-64 max-w-4xl items-center justify-center">
              <div class="text-center text-sm text-slate-400">
                <Icon name="refresh" size={24} class="mx-auto mb-3 animate-spin" />
                {busyStage}
              </div>
            </div>
          )}

          {result && !busy && result.status === 'WAITING_USER' && (
            <div class="mx-auto max-w-4xl">
              <section class="rounded-2xl border border-blue-200 bg-blue-50/60 p-5 dark:border-blue-900/70 dark:bg-blue-950/20 sm:p-6">
                <div class="text-sm font-semibold text-blue-900 dark:text-blue-100">需要你确认</div>
                <div class="mt-3 text-[15px] leading-7 text-slate-700 dark:text-slate-200">
                  {result.clarification_question}
                </div>
                {result.choices.length > 0 && (
                  <div class="mt-4 flex flex-wrap gap-2">
                    {result.choices.map(choice => (
                      <button
                        key={choice}
                        onClick={() => void continueQuery(choice)}
                        class="rounded-xl border border-blue-200 bg-white px-3 py-2 text-sm text-blue-700 hover:border-blue-400 dark:border-blue-800 dark:bg-slate-900 dark:text-blue-300"
                      >
                        {choice}
                      </button>
                    ))}
                  </div>
                )}
                <div class="mt-5 flex flex-col gap-3 sm:flex-row">
                  <input
                    value={clarification}
                    onInput={event => setClarification((event.target as HTMLInputElement).value)}
                    onKeyDown={event => {
                      if (event.key === 'Enter') void continueQuery();
                    }}
                    placeholder="补充说明…"
                    class="min-w-0 flex-1 rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950"
                  />
                  <button
                    onClick={() => void continueQuery()}
                    disabled={!clarification.trim()}
                    class="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50 dark:bg-blue-500"
                  >
                    继续查询
                  </button>
                </div>
              </section>
            </div>
          )}

          {result && !busy && result.status !== 'WAITING_USER' && (
            <div class="mx-auto max-w-5xl space-y-7">
              <section class="rounded-2xl border border-slate-200 bg-slate-50/70 p-5 dark:border-slate-800 dark:bg-slate-950/50 sm:p-6">
                <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
                  <div class="text-sm font-semibold">回答</div>
                  <div class="flex flex-wrap items-center gap-2">
                    {result.evidence_only && (
                      <span class="rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">
                        证据有限
                      </span>
                    )}
                    <SecondaryButton
                      onClick={() => void downloadSources()}
                      disabled={!evidenceIds.length || packageBusy}
                      icon="download"
                    >
                      {packageBusy ? '正在打包…' : '下载参考资料'}
                    </SecondaryButton>
                  </div>
                </div>
                <div class="whitespace-pre-wrap text-[15px] leading-8 text-slate-700 dark:text-slate-200">
                  {result.answer || '本次查询未生成可交付回答。'}
                </div>
                {(result.status === 'ANSWERED' || result.status === 'INSUFFICIENT') && (
                  <div class="mt-5 border-t border-slate-200 pt-4 text-xs text-slate-400 dark:border-slate-800">
                    <div class="flex flex-wrap items-center gap-2">
                      <span>这次查询是否解决了你的问题？</span>
                      <button
                        onClick={() => void sendFeedback('POSITIVE')}
                        class={`rounded-lg border px-2.5 py-1.5 ${feedback === 'POSITIVE' ? 'border-blue-400 bg-blue-50 text-blue-700 dark:bg-blue-950/30' : 'border-slate-200 hover:border-blue-300 dark:border-slate-700'}`}
                      >
                        已解决
                      </button>
                      <button
                        onClick={() => setFeedbackMenu(value => !value)}
                        class={`rounded-lg border px-2.5 py-1.5 ${feedback === 'NEGATIVE' ? 'border-blue-400 bg-blue-50 text-blue-700 dark:bg-blue-950/30' : 'border-slate-200 hover:border-blue-300 dark:border-slate-700'}`}
                      >
                        需改进
                      </button>
                    </div>
                    {feedbackMenu && (
                      <div class="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                        {NEGATIVE_FEEDBACK.map(([category, label]) => (
                          <button
                            key={category}
                            onClick={() => void sendFeedback('NEGATIVE', category)}
                            class="rounded-lg border border-slate-200 bg-white px-3 py-2 text-left text-xs text-slate-600 transition hover:border-blue-300 hover:text-blue-700 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
                          >
                            {label}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </section>

              <section>
                <SectionTitle title={`参考资料 ${result.citations.length} 条`} />
                <div class="mt-4 grid gap-3 lg:grid-cols-2">
                  {result.citations.map(citation => (
                    <button
                      key={`${citation.index}-${citation.evidence.evidence_id}`}
                      onClick={() => onOpenEvidence(citation.evidence)}
                      class="group rounded-2xl border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-blue-300 dark:border-slate-800 dark:bg-slate-900"
                    >
                      <div class="flex items-start justify-between gap-3">
                        <span class="flex h-7 min-w-7 items-center justify-center rounded-lg bg-blue-600 px-2 text-xs font-semibold text-white dark:bg-blue-500">
                          [{citation.index}]
                        </span>
                        <Icon
                          name="arrow"
                          size={15}
                          class="text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-blue-500"
                        />
                      </div>
                      <div class="mt-3 flex min-w-0 items-center gap-2 text-xs font-semibold text-slate-700 dark:text-slate-200">
                        <Icon name="file" size={14} class="shrink-0 text-slate-400" />
                        <span class="truncate">{citation.evidence.source_filename || '未知文件'}</span>
                      </div>
                      <div class="mt-2 line-clamp-4 text-sm leading-6 text-slate-600 dark:text-slate-300">
                        {citation.evidence.raw_content}
                      </div>
                      <div class="mt-3 text-[11px] font-medium text-blue-600 dark:text-blue-400">
                        查看原文
                      </div>
                    </button>
                  ))}
                </div>
              </section>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function historyStatus(status: EnterpriseQuerySessionSummary['status']) {
  return ({ RUNNING: '查询中', WAITING_USER: '待确认', ANSWERED: '已回答', INSUFFICIENT: '证据不足', FAILED: '未完成' } as Record<string, string>)[status] || status;
}

function formatHistoryTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString([], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}
