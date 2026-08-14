import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import type { EnterpriseUser } from '../lib/api';
import { Icon, type IconName } from './ui';

export type EnterpriseSection = 'dashboard' | 'ask' | 'search' | 'documents' | 'wiki' | 'graph' | 'evidence' | 'tasks' | 'audit' | 'admin';

const navigation: Array<{ id: EnterpriseSection; label: string; description: string; icon: IconName; adminOnly?: boolean }> = [
  { id: 'dashboard', label: '总览', description: 'Knowledge health', icon: 'dashboard' },
  { id: 'ask', label: '企业问答', description: 'Answer with citations', icon: 'sparkles' },
  { id: 'search', label: '高级检索', description: 'Evidence-first search', icon: 'search' },
  { id: 'documents', label: '文档资产', description: 'Versions & lifecycle', icon: 'documents' },
  { id: 'wiki', label: '企业百科', description: 'Claims & citations', icon: 'wiki' },
  { id: 'graph', label: '关系图谱', description: 'Evidence-backed graph', icon: 'graph' },
  { id: 'evidence', label: '证据中心', description: 'Raw provenance', icon: 'evidence' },
  { id: 'tasks', label: '任务中心', description: 'My ingestion progress', icon: 'tasks' },
  { id: 'audit', label: '审计日志', description: 'Actor · action · target', icon: 'shield', adminOnly: true },
  { id: 'admin', label: '系统管理', description: 'Users & runtime', icon: 'admin', adminOnly: true },
];

const pageMeta: Record<EnterpriseSection, { title: string; description: string }> = {
  dashboard: { title: '企业知识总览', description: '把文档、事实、Wiki、Graph 与 Evidence 作为一套可追溯知识资产持续管理。' },
  ask: { title: '企业知识问答', description: '先检索可验证知识，再基于 Evidence 回答；每个引用都可以继续打开原文。' },
  search: { title: '高级知识检索', description: '四路候选统一 Rerank，所有最终结果必须通过 Evidence Resolver 回到 Raw。' },
  documents: { title: '文档知识资产', description: '批量上传、后台解压、逐文件 Metadata 调整，并跟踪每个 Version 的知识生命周期。' },
  wiki: { title: '企业百科', description: '保留百科式可视化阅读，以 Claim 为核心查看事实、章节和原始证据。' },
  graph: { title: '关系图谱', description: '保留 Evidence-backed Graph 可视化，支持关系浏览与 1–2 hop 有界扩展。' },
  evidence: { title: 'Evidence Center', description: '所有高阶知识的可信根：Document Version、原始位置、原始内容和源文件定位。' },
  tasks: { title: '知识处理任务', description: '统一查看文件解析、Embedding、索引、激活与知识编译的真实进度。' },
  audit: { title: '审计日志', description: '按 actor / action / target / outcome 查看有业务和安全意义的操作历史。' },
  admin: { title: '系统管理', description: '管理企业账号、运行能力与关键质量门，不展示任何敏感凭据。' },
};

export function EnterpriseShell({ section, onNavigate, children, error, busy, onRefresh, user, onLogout, onChangePassword }: {
  section: EnterpriseSection;
  onNavigate: (section: EnterpriseSection) => void;
  children: ComponentChildren;
  error?: string;
  busy?: boolean;
  onRefresh?: () => void;
  user: EnterpriseUser;
  onLogout: () => void;
  onChangePassword: () => void;
}) {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const meta = pageMeta[section];
  const visibleNavigation = navigation.filter(item => !item.adminOnly || user.role === 'ADMIN');

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
        {visibleNavigation.map(item => {
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
            <button onClick={() => navigate('ask')} class="hidden items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm font-medium text-slate-600 shadow-sm transition hover:border-slate-300 hover:text-slate-950 sm:flex dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:text-white"><Icon name="sparkles" size={16} />问知识</button>
            <div class="relative">
              <button onClick={() => setAccountOpen(value => !value)} class="flex items-center gap-2 rounded-xl border border-slate-200 bg-white p-1.5 pr-2.5 shadow-sm transition hover:border-slate-300 dark:border-slate-700 dark:bg-slate-900">
                <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-950 text-xs font-semibold text-white dark:bg-white dark:text-slate-950">{(user.display_name || user.username).slice(0, 1).toUpperCase()}</div>
                <div class="hidden text-left md:block"><div class="max-w-28 truncate text-xs font-semibold text-slate-700 dark:text-slate-200">{user.display_name || user.username}</div><div class="text-[10px] uppercase tracking-wide text-slate-400">{user.role}</div></div>
              </button>
              {accountOpen && <div class="absolute right-0 top-12 z-50 w-56 overflow-hidden rounded-2xl border border-slate-200 bg-white p-2 shadow-xl shadow-slate-200/50 dark:border-slate-700 dark:bg-slate-900 dark:shadow-none">
                <div class="border-b border-slate-100 px-3 py-2.5 dark:border-slate-800"><div class="truncate text-sm font-semibold">{user.display_name || user.username}</div><div class="mt-0.5 truncate text-xs text-slate-400">@{user.username} · {user.role}</div></div>
                <button onClick={() => { setAccountOpen(false); onChangePassword(); }} class="mt-1 flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800"><Icon name="shield" size={15}/>修改密码</button>
                <button onClick={() => { setAccountOpen(false); onLogout(); }} class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-950/30"><Icon name="arrow" size={15}/>退出登录</button>
              </div>}
            </div>
          </div>
        </div>
      </header>

      {error && <div class="mx-4 mt-4 flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 sm:mx-6 lg:mx-8 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300"><Icon name="warning" size={18} class="mt-0.5 shrink-0" /><div class="break-words">{error}</div></div>}
      <main class="mx-auto max-w-[1500px] p-4 sm:p-6 lg:p-8">{children}</main>
    </div>
  </div>;
}
