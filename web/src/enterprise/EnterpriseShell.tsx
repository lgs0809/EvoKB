import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { Icon, type IconName } from './ui';

export type EnterpriseSection = 'dashboard' | 'search' | 'documents' | 'wiki' | 'graph' | 'evidence' | 'tasks' | 'admin';

const navigation: Array<{ id: EnterpriseSection; label: string; description: string; icon: IconName }> = [
  { id: 'dashboard', label: '总览', description: 'Knowledge health', icon: 'dashboard' },
  { id: 'search', label: '知识检索', description: 'Evidence-first search', icon: 'search' },
  { id: 'documents', label: '文档资产', description: 'Versions & lifecycle', icon: 'documents' },
  { id: 'wiki', label: '企业百科', description: 'Claims & citations', icon: 'wiki' },
  { id: 'graph', label: '关系图谱', description: 'Evidence-backed graph', icon: 'graph' },
  { id: 'evidence', label: '证据中心', description: 'Raw provenance', icon: 'evidence' },
  { id: 'tasks', label: '任务中心', description: 'Compile pipeline', icon: 'tasks' },
  { id: 'admin', label: '系统状态', description: 'Runtime capabilities', icon: 'admin' },
];

const pageMeta: Record<EnterpriseSection, { title: string; description: string }> = {
  dashboard: { title: '企业知识总览', description: '把文档、事实、Wiki、Graph 与 Evidence 作为一套可追溯知识资产持续管理。' },
  search: { title: '可信知识检索', description: '四路候选统一 Rerank，所有最终结果必须通过 Evidence Resolver 回到 Raw。' },
  documents: { title: '文档知识资产', description: '从原始文件到 Summary / Wiki / Graph，查看每一个 Version 的知识生命周期。' },
  wiki: { title: '企业百科', description: '以 Claim 为核心阅读知识，并直接查看支撑该 Claim 的事实与原始证据。' },
  graph: { title: '关系图谱', description: '只浏览拥有 ACTIVE Evidence Support 的企业关系，支持 1–2 hop 有界扩展。' },
  evidence: { title: 'Evidence Center', description: '所有高阶知识的可信根：Document Version、原始位置与原始内容。' },
  tasks: { title: '知识编译任务', description: '查看解析、编译和知识更新任务的状态、耗时与失败原因。' },
  admin: { title: '系统状态', description: '检查企业知识库运行能力与关键质量门，不展示任何敏感凭据。' },
};

export function EnterpriseShell({ section, onNavigate, children, error, busy, onRefresh }: {
  section: EnterpriseSection;
  onNavigate: (section: EnterpriseSection) => void;
  children: ComponentChildren;
  error?: string;
  busy?: boolean;
  onRefresh?: () => void;
}) {
  const [mobileOpen, setMobileOpen] = useState(false);
  const meta = pageMeta[section];

  const navigate = (next: EnterpriseSection) => {
    onNavigate(next);
    setMobileOpen(false);
  };

  const toggleTheme = () => {
    const dark = document.documentElement.classList.toggle('dark');
    localStorage.setItem('sage-wiki-theme', dark ? 'dark' : 'light');
  };

  return <div class="min-h-screen bg-[#f6f8fb] text-slate-900 dark:bg-slate-950 dark:text-slate-100">
    {mobileOpen && <button class="fixed inset-0 z-30 bg-slate-950/30 backdrop-blur-[1px] lg:hidden" aria-label="关闭导航" onClick={() => setMobileOpen(false)} />}
    <aside class={`fixed inset-y-0 left-0 z-40 flex w-[272px] flex-col border-r border-slate-200/80 bg-white transition-transform dark:border-slate-800 dark:bg-slate-900 lg:translate-x-0 ${mobileOpen ? 'translate-x-0' : '-translate-x-full'}`}>
      <div class="flex h-20 items-center gap-3 border-b border-slate-100 px-5 dark:border-slate-800">
        <div class="flex h-10 w-10 items-center justify-center rounded-2xl bg-slate-950 text-white shadow-lg shadow-slate-950/15 dark:bg-white dark:text-slate-950"><Icon name="layers" size={21} /></div>
        <div class="min-w-0"><div class="truncate text-sm font-semibold tracking-tight">Enterprise Knowledge</div><div class="mt-0.5 text-[11px] font-medium uppercase tracking-[0.15em] text-slate-400">Evidence-first</div></div>
      </div>

      <nav class="flex-1 space-y-1 overflow-y-auto px-3 py-5">
        <div class="mb-2 px-3 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-400">Knowledge workspace</div>
        {navigation.map(item => {
          const active = item.id === section;
          return <button key={item.id} onClick={() => navigate(item.id)} class={`group flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition ${active ? 'bg-slate-950 text-white shadow-sm dark:bg-white dark:text-slate-950' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-950 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white'}`}>
            <div class={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${active ? 'bg-white/10 dark:bg-slate-950/10' : 'bg-slate-100 text-slate-500 group-hover:bg-white dark:bg-slate-800 dark:text-slate-400 dark:group-hover:bg-slate-700'}`}><Icon name={item.icon} size={18} /></div>
            <div class="min-w-0"><div class="text-sm font-semibold">{item.label}</div><div class={`truncate text-[11px] ${active ? 'text-white/60 dark:text-slate-950/55' : 'text-slate-400'}`}>{item.description}</div></div>
          </button>;
        })}
      </nav>

      <div class="border-t border-slate-100 p-3 dark:border-slate-800">
        <a href="/wiki/" class="flex items-center justify-between rounded-xl px-3 py-2.5 text-sm font-medium text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white"><span>返回 Sage-Wiki</span><Icon name="arrow" size={15} /></a>
      </div>
    </aside>

    <div class="lg:pl-[272px]">
      <header class="sticky top-0 z-20 border-b border-slate-200/70 bg-[#f6f8fb]/90 backdrop-blur-xl dark:border-slate-800 dark:bg-slate-950/85">
        <div class="flex min-h-20 items-center gap-4 px-4 sm:px-6 lg:px-8">
          <button class="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-600 shadow-sm lg:hidden dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300" onClick={() => setMobileOpen(true)} aria-label="打开导航"><Icon name="menu" /></button>
          <div class="min-w-0 flex-1"><h1 class="truncate text-lg font-semibold tracking-tight text-slate-950 dark:text-white">{meta.title}</h1><p class="hidden truncate text-xs text-slate-500 sm:block dark:text-slate-400">{meta.description}</p></div>
          <div class="flex items-center gap-2">
            {onRefresh && <button onClick={onRefresh} disabled={busy} title="刷新当前数据" class="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:text-slate-900 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400 dark:hover:text-white"><Icon name="refresh" class={busy ? 'animate-spin' : ''} /></button>}
            <button onClick={toggleTheme} title="切换明暗主题" class="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400 dark:hover:text-white"><Icon name="sparkles" /></button>
            <button onClick={() => navigate('search')} class="hidden items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm font-medium text-slate-600 shadow-sm transition hover:border-slate-300 hover:text-slate-950 sm:flex dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:text-white"><Icon name="search" size={16} />搜索知识</button>
          </div>
        </div>
      </header>

      {error && <div class="mx-4 mt-4 flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 sm:mx-6 lg:mx-8 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300"><Icon name="warning" size={18} class="mt-0.5 shrink-0" /><div class="break-words">{error}</div></div>}
      <main class="mx-auto max-w-[1500px] p-4 sm:p-6 lg:p-8">{children}</main>
    </div>
  </div>;
}
