import { useEffect, useMemo, useState } from 'preact/hooks';
import { fetchEnterpriseEvidence, fetchEnterpriseQuality, type EnterpriseEvidence, type EnterpriseQuality, type EnterpriseQualityIssue } from '../lib/api';
import { EmptyState, Icon, LoadingBlock, SectionTitle, formatDate, shortID } from './ui';
import type { EnterpriseSection } from './EnterpriseShell';

export function QualityPage({ onNavigate, onOpenEvidence, onError }: {
  onNavigate: (section: EnterpriseSection) => void;
  onOpenEvidence: (evidence: EnterpriseEvidence) => void;
  onError: (message: string) => void;
}) {
  const [quality, setQuality] = useState<EnterpriseQuality | null>(null);
  const [loading, setLoading] = useState(true);
  const [type, setType] = useState('ALL');

  const load = async () => {
    setLoading(true);
    try {
      setQuality(await fetchEnterpriseQuality(200));
      onError('');
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, []);

  const issueTypes = useMemo(() => Array.from(new Set((quality?.issues || []).map(item => item.issue_type))).sort(), [quality]);
  const issues = useMemo(() => (quality?.issues || []).filter(item => type === 'ALL' || item.issue_type === type), [quality, type]);
  const totalIssues = quality ? quality.summary_grounding_failures + quality.summary_coverage_failures + quality.wiki_grounding_failures + quality.wiki_temporal_conflicts + quality.relation_conflicts + quality.unresolved_entities + quality.failed_generation_tasks : 0;

  const openEvidence = async (id: string) => {
    try { onOpenEvidence(await fetchEnterpriseEvidence(id)); onError(''); }
    catch (err) { onError(String(err instanceof Error ? err.message : err)); }
  };

  return <div class="space-y-6">
    <SectionTitle title="知识质量" description="按 Grounding、Coverage、实体归一、关系冲突和生成失败查看可定位问题，不使用无法解释的综合分数。" action={<button onClick={() => void load()} disabled={loading} class="inline-flex items-center gap-2 rounded-xl border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-semibold text-blue-700 disabled:opacity-50 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-300"><Icon name="refresh" size={14} class={loading ? 'animate-spin' : ''}/>刷新</button>}/>

    {loading ? <LoadingBlock rows={6}/> : quality && <>
      <section class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7">
        <QualityMetric label="Summary Grounding" value={quality.summary_grounding_failures} tone="high"/>
        <QualityMetric label="Summary Coverage" value={quality.summary_coverage_failures} tone="high"/>
        <QualityMetric label="Wiki Grounding" value={quality.wiki_grounding_failures} tone="high"/>
        <QualityMetric label="Wiki 时间冲突" value={quality.wiki_temporal_conflicts} tone="high"/>
        <QualityMetric label="关系冲突" value={quality.relation_conflicts} tone="high"/>
        <QualityMetric label="实体未归一" value={quality.unresolved_entities} tone="medium"/>
        <QualityMetric label="生成失败" value={quality.failed_generation_tasks} tone="high"/>
      </section>

      <section class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-5">
        <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between"><div><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">质量问题 {totalIssues} 项</div><div class="mt-1 text-xs text-slate-400">列表最多展示 200 条最近问题；数字来自完整事实表统计。</div></div><div class="flex gap-2 overflow-x-auto"><button onClick={() => setType('ALL')} class={filterClass(type === 'ALL')}>全部</button>{issueTypes.map(item => <button key={item} onClick={() => setType(item)} class={filterClass(type === item)}>{issueLabel(item)}</button>)}</div></div>
      </section>

      <section class="space-y-3">
        {issues.map(issue => <IssueCard key={`${issue.issue_type}-${issue.artifact_id}-${issue.created_at}`} issue={issue} onOpenEvidence={openEvidence} onNavigate={onNavigate}/>) }
        {issues.length === 0 && <EmptyState icon="quality" title="当前筛选下没有质量问题" description="没有发现符合当前类型的 Grounding、Coverage、冲突或生成失败记录。"/>}
      </section>
    </>}
  </div>;
}

function IssueCard({ issue, onOpenEvidence, onNavigate }: { issue: EnterpriseQualityIssue; onOpenEvidence: (id: string) => Promise<void>; onNavigate: (section: EnterpriseSection) => void }) {
  return <article class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:p-5">
    <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between"><div class="flex min-w-0 gap-3"><div class={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${issue.severity === 'HIGH' ? 'bg-rose-50 text-rose-600 dark:bg-rose-950/30 dark:text-rose-300' : 'bg-amber-50 text-amber-600 dark:bg-amber-950/30 dark:text-amber-300'}`}><Icon name="warning" size={17}/></div><div class="min-w-0"><div class="flex flex-wrap items-center gap-2"><span class="text-sm font-semibold text-slate-700 dark:text-slate-200">{issue.title}</span><span class="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-500 dark:bg-slate-800 dark:text-slate-300">{issueLabel(issue.issue_type)}</span></div><div class="mt-2 break-words text-xs leading-5 text-slate-500 dark:text-slate-400">{issue.detail}</div><div class="mt-2 flex flex-wrap gap-3 text-[11px] text-slate-400">{issue.version_id && <span>版本 {shortID(issue.version_id)}</span>}{issue.artifact_id && <span>Artifact {shortID(issue.artifact_id)}</span>}<span>{formatDate(issue.created_at)}</span></div></div></div><div class="flex shrink-0 flex-wrap gap-2"><button onClick={() => onNavigate(targetSection(issue.issue_type))} class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-500 transition hover:border-blue-300 hover:text-blue-700 dark:border-slate-700 dark:text-slate-300">查看相关页面</button>{issue.evidence_ids.slice(0,3).map((id,index) => <button key={id} onClick={() => void onOpenEvidence(id)} class="rounded-lg border border-blue-100 bg-blue-50 px-2.5 py-1.5 text-xs font-medium text-blue-700 transition hover:border-blue-300 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-300">原文 {index+1}</button>)}</div></div>
  </article>;
}

function QualityMetric({ label, value, tone }: { label: string; value: number; tone: 'high' | 'medium' }) { return <div class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900"><div class="text-[11px] font-medium text-slate-400">{label}</div><div class={`mt-2 text-2xl font-semibold ${value > 0 ? tone === 'high' ? 'text-rose-600 dark:text-rose-300' : 'text-amber-600 dark:text-amber-300' : 'text-emerald-600 dark:text-emerald-300'}`}>{value}</div></div>; }
function filterClass(active: boolean) { return `whitespace-nowrap rounded-xl px-3 py-2 text-xs font-semibold transition ${active ? 'bg-blue-600 text-white dark:bg-blue-500' : 'bg-slate-100 text-slate-500 hover:bg-blue-50 hover:text-blue-700 dark:bg-slate-800 dark:text-slate-300'}`; }
function issueLabel(value: string) { return ({ SUMMARY_QUALITY: 'Summary 质量', WIKI_CLAIM: 'Wiki Claim', RELATION_CONFLICT: '关系冲突', ENTITY_RESOLUTION: '实体归一', GENERATION_TASK: '生成任务' } as Record<string,string>)[value] || value; }
function targetSection(value: string): EnterpriseSection { if (value === 'WIKI_CLAIM') return 'wiki'; if (value === 'RELATION_CONFLICT' || value === 'ENTITY_RESOLUTION') return 'graph'; if (value === 'GENERATION_TASK') return 'tasks'; return 'documents'; }
