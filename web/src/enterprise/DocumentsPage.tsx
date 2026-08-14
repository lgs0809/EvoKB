import { useEffect, useMemo, useState } from 'preact/hooks';
import { recommendEnterpriseDistill, type EnterpriseDocumentVersion } from '../lib/api';
import type { EnterpriseSection } from './EnterpriseShell';
import { EmptyState, Icon, PrimaryButton, SecondaryButton, SectionTitle, StatusBadge, formatDate, shortID } from './ui';

export function DocumentsPage({ documents, onReload, onNavigate, onError }: {
  documents: EnterpriseDocumentVersion[];
  onReload: () => Promise<void> | void;
  onNavigate: (section: EnterpriseSection) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('ALL');
  const [selected, setSelected] = useState<EnterpriseDocumentVersion | null>(documents[0] || null);
  const [busy, setBusy] = useState(false);

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
      <SectionTitle eyebrow="Knowledge assets" title="文档与版本" description="每个 Document 可以有多个 Version；只有 ACTIVE Version 的 Raw Evidence 进入当前可信知识链。" />
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

            {selected.recommendation === 'RECOMMENDED' ? <div class="rounded-xl border border-violet-200 bg-violet-50 p-3 text-xs text-violet-800 dark:border-violet-900/60 dark:bg-violet-950/30 dark:text-violet-300"><div class="font-semibold">已推荐高价值编译</div><div class="mt-1">{selected.recommendation_reason || '由有效使用或人工标记触发'}</div></div> : selected.lifecycle === 'ACTIVE' && <PrimaryButton onClick={() => void markDistill(selected.version_id)} disabled={busy} icon="sparkles">{busy ? '处理中…' : '标记为高价值 Source'}</PrimaryButton>}
            <div class="grid grid-cols-2 gap-2"><SecondaryButton onClick={() => onNavigate('evidence')} icon="evidence">查看 Evidence</SecondaryButton><SecondaryButton onClick={() => onNavigate('wiki')} icon="wiki">查看 Wiki</SecondaryButton></div>
          </div>
        </div> : <EmptyState icon="documents" title="选择一个 Document Version" description="查看 Metadata、知识生命周期、版本历史与 Distillation 状态。" />}
      </aside>
    </section>
  </div>;
}

function LifecycleStep({ icon, title, detail, done, neutral, last }: { icon: 'file' | 'layers' | 'sparkles' | 'wiki' | 'shield'; title: string; detail: string; done?: boolean; neutral?: boolean; last?: boolean }) {
  return <div class="flex gap-3"><div class="flex flex-col items-center"><div class={`flex h-9 w-9 items-center justify-center rounded-xl border ${done ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300' : neutral ? 'border-blue-200 bg-blue-50 text-blue-600 dark:border-blue-900/60 dark:bg-blue-950/40 dark:text-blue-300' : 'border-slate-200 bg-slate-50 text-slate-400 dark:border-slate-700 dark:bg-slate-800'}`}><Icon name={icon} size={16}/></div>{!last && <div class={`h-8 w-px ${done ? 'bg-emerald-200 dark:bg-emerald-900' : 'bg-slate-200 dark:bg-slate-700'}`}/>}</div><div class="min-w-0 pb-4"><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">{title}</div><div class="mt-0.5 text-xs leading-5 text-slate-400">{detail}</div></div></div>;
}

function Info({ label, value }: { label: string; value: string }) {
  return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 break-words font-medium text-slate-700 dark:text-slate-200">{value}</div></div>;
}
