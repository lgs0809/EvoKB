import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import type { EnterpriseUser } from '../lib/api';
import { Icon, type IconName } from './ui';

export type EnterpriseSection = 'dashboard' | 'ask' | 'history' | 'search' | 'documents' | 'wiki' | 'graph' | 'evidence' | 'ingestion' | 'tasks' | 'quality' | 'models' | 'audit' | 'admin';

type NavigationItem = { id: EnterpriseSection; label: string; icon: IconName; adminOnly?: boolean; userOnly?: boolean };

const navigationItems: NavigationItem[] = [
  { id: 'dashboard', label: '首页', icon: 'dashboard', adminOnly: true },
  { id: 'ask', label: '知识查询', icon: 'chat' },
  { id: 'history', label: '查询记录', icon: 'history' },
  { id: 'documents', label: '资料管理', icon: 'documents' },
  { id: 'wiki', label: '企业百科', icon: 'wiki' },
  { id: 'graph', label: '关系图谱', icon: 'graph' },
  { id: 'ingestion', label: '上传记录', icon: 'upload', userOnly: true },
  { id: 'ingestion', label: '入库记录', icon: 'upload', adminOnly: true },
];

const adminNavigationItems: NavigationItem[] = [
  { id: 'tasks', label: '知识生成', icon: 'sparkles', adminOnly: true },
  { id: 'quality', label: '知识质量', icon: 'quality', adminOnly: true },
  { id: 'search', label: '检索测试', icon: 'test', adminOnly: true },
  { id: 'models', label: '模型配置', icon: 'models', adminOnly: true },
  { id: 'audit', label: '审计日志', icon: 'audit', adminOnly: true },
  { id: 'admin', label: '用户与系统', icon: 'users', adminOnly: true },
];

const adminNavigationSections = new Set<EnterpriseSection>(adminNavigationItems.map(item => item.id));

const pageMeta: Record<EnterpriseSection, { title: string; description: string }> = {
  dashboard: { title: '首页', description: '查看知识资产、任务和质量概况。' },
  ask: { title: '知识查询', description: '查询企业知识并查看引用来源。' },
  history: { title: '查询记录', description: '管理历史查询、查询组和已打包的引用资料。' },
  search: { title: '检索测试', description: '测试检索结果与排序效果。' },
  documents: { title: '资料管理', description: '管理资料及其版本。' },
  wiki: { title: '企业百科', description: '按主题浏览企业知识。' },
  graph: { title: '关系图谱', description: '查看企业实体与关系。' },
  evidence: { title: '证据查看', description: '查看引用原文和资料位置。' },
  ingestion: { title: '入库记录', description: '查看资料上传与入库进度。' },
  tasks: { title: '知识生成', description: '查看摘要、关系和百科的生成进度。' },
  quality: { title: '知识质量', description: '定位 Grounding、Coverage、实体、关系与百科质量问题。' },
  models: { title: '模型配置', description: '配置 LLM、Embedding 与 Rerank，并让后续调用即时生效。' },
  audit: { title: '审计日志', description: '查看关键操作记录。' },
  admin: { title: '用户与系统', description: '管理用户、权限和系统状态。' },
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
  const [managementOpen, setManagementOpen] = useState(() => adminNavigationSections.has(section));
  const [darkMode, setDarkMode] = useState(() => {
    const stored = localStorage.getItem('evokb-theme') ?? localStorage.getItem('enterprise-knowledge-base-theme');
    const initialDark = stored === 'dark' || (stored !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.toggle('dark', initialDark);
    return initialDark;
  });
  const meta = section === 'ingestion' && user.role !== 'ADMIN'
    ? { title: '上传记录', description: '查看我的资料上传与处理进度。' }
    : pageMeta[section];
  const visibleItems = navigationItems.filter(item => (!item.adminOnly || user.role === 'ADMIN') && (!item.userOnly || user.role !== 'ADMIN'));
  const visibleAdminItems = user.role === 'ADMIN' ? adminNavigationItems : [];

  useEffect(() => {
    if (adminNavigationSections.has(section)) setManagementOpen(true);
  }, [section]);

  const navigate = (next: EnterpriseSection) => {
    onNavigate(next);
    setMobileOpen(false);
  };

  const toggleTheme = () => {
    const nextDark = !darkMode;
    document.documentElement.classList.toggle('dark', nextDark);
    localStorage.setItem('evokb-theme', nextDark ? 'dark' : 'light');
    setDarkMode(nextDark);
  };

  return <div class="min-h-screen bg-[#f6f8fb] text-slate-900 dark:bg-slate-950 dark:text-slate-100">
    {mobileOpen && <button class="fixed inset-0 z-30 bg-slate-950/30 backdrop-blur-[1px] lg:hidden" aria-label="关闭导航" onClick={() => setMobileOpen(false)} />}
    <aside class={`fixed inset-y-0 left-0 z-40 flex w-[236px] flex-col border-r border-slate-200/80 bg-white transition-transform dark:border-slate-800 dark:bg-slate-900 lg:translate-x-0 ${mobileOpen ? 'translate-x-0' : '-translate-x-full'}`}>
      <div class="flex h-20 items-center gap-3 border-b border-slate-100 px-5 dark:border-slate-800">
        <div class="flex h-10 w-10 items-center justify-center rounded-2xl bg-blue-600 text-white shadow-lg shadow-blue-600/20 dark:bg-blue-500"><Icon name="layers" size={21} /></div>
        <div class="min-w-0"><div class="truncate text-sm font-semibold tracking-tight">EvoKB</div><div class="mt-0.5 text-[11px] text-slate-400">企业知识管理与查询</div></div>
      </div>

      <nav class="flex-1 overflow-y-auto px-3 py-4">
        <div class="space-y-1">{visibleItems.map(item => <NavigationButton key={`${item.id}-${item.label}`} item={item} active={item.id === section} onClick={() => navigate(item.id)} />)}</div>
        {visibleAdminItems.length > 0 && <div class="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800">
          <button onClick={() => setManagementOpen(value => !value)} class={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm font-medium transition ${adminNavigationSections.has(section) ? 'text-blue-700 dark:text-blue-300' : 'text-slate-500 hover:bg-slate-50 dark:text-slate-400 dark:hover:bg-slate-800/60'}`}>
            <Icon name="settings" size={17} class="text-slate-400" />
            <span class="flex-1">管理</span>
            <Icon name="chevron" size={15} class={`transition-transform ${managementOpen ? 'rotate-90' : ''}`} />
          </button>
          {managementOpen && <div class="mt-1 space-y-1 pl-2">{visibleAdminItems.map(item => <NavigationButton key={`${item.id}-${item.label}`} item={item} active={item.id === section} onClick={() => navigate(item.id)} compact />)}</div>}
        </div>}
      </nav>
    </aside>

    <div class="lg:pl-[236px]">
      <header class="sticky top-0 z-20 border-b border-slate-200/70 bg-[#f6f8fb]/90 backdrop-blur-xl dark:border-slate-800 dark:bg-slate-950/85">
        <div class="flex min-h-20 items-center gap-4 px-4 sm:px-6 lg:px-8">
          <button class="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-600 shadow-sm lg:hidden dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300" onClick={() => setMobileOpen(true)} aria-label="打开导航"><Icon name="menu" /></button>
          <div class="min-w-0 flex-1"><h1 class="truncate text-lg font-semibold tracking-tight text-slate-950 dark:text-white">{meta.title}</h1><p class="hidden truncate text-xs text-slate-500 sm:block dark:text-slate-400">{meta.description}</p></div>
          <div class="flex items-center gap-2">
            {onRefresh && <button onClick={onRefresh} disabled={busy} title="刷新" class="flex h-10 w-10 items-center justify-center rounded-xl border border-blue-200 bg-blue-50/70 text-blue-600 shadow-sm transition hover:bg-blue-100 disabled:opacity-50 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60"><Icon name="refresh" class={busy ? 'animate-spin' : ''} /></button>}
            <button onClick={toggleTheme} title={darkMode ? '切换到浅色模式' : '切换到深色模式'} aria-label={darkMode ? '切换到浅色模式' : '切换到深色模式'} class="flex h-10 w-10 items-center justify-center rounded-xl border border-blue-200 bg-blue-50/70 text-blue-600 shadow-sm transition hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60"><Icon name={darkMode ? 'sun' : 'moon'} /></button>
            {section !== 'ask' && <button onClick={() => navigate('ask')} class="hidden rounded-xl border border-blue-200 bg-blue-50/70 px-3.5 py-2.5 text-sm font-medium text-blue-700 shadow-sm transition hover:border-blue-300 hover:bg-blue-100 sm:block dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60">查知识</button>}
            <div class="relative">
              <button onClick={() => setAccountOpen(value => !value)} class="flex items-center gap-2 rounded-xl border border-blue-200 bg-blue-50/50 p-1.5 pr-2.5 shadow-sm transition hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/20 dark:hover:bg-blue-950/50">
                <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600 text-xs font-semibold text-white dark:bg-blue-500">{(user.display_name || user.username).slice(0, 1).toUpperCase()}</div>
                <div class="hidden text-left md:block"><div class="max-w-28 truncate text-xs font-semibold text-slate-700 dark:text-slate-200">{user.display_name || user.username}</div><div class="text-[10px] tracking-wide text-slate-400">{user.role === 'ADMIN' ? '管理员' : '普通用户'}</div></div>
              </button>
              {accountOpen && <div class="absolute right-0 top-12 z-50 w-56 overflow-hidden rounded-2xl border border-slate-200 bg-white p-2 shadow-xl shadow-slate-200/50 dark:border-slate-700 dark:bg-slate-900 dark:shadow-none">
                <div class="border-b border-slate-100 px-3 py-2.5 dark:border-slate-800"><div class="truncate text-sm font-semibold">{user.display_name || user.username}</div><div class="mt-0.5 truncate text-xs text-slate-400">@{user.username} · {user.role === 'ADMIN' ? '管理员' : '普通用户'}</div></div>
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

function NavigationButton({ item, active, onClick, compact = false }: { item: NavigationItem; active: boolean; onClick: () => void; compact?: boolean }) {
  return <button onClick={onClick} class={`flex w-full items-center gap-3 rounded-xl ${compact ? 'px-3 py-2' : 'px-3 py-2.5'} text-left text-sm font-medium transition ${active ? 'bg-blue-600 text-white shadow-sm shadow-blue-600/15 dark:bg-blue-500' : 'text-slate-600 hover:bg-blue-50 hover:text-blue-700 dark:text-slate-300 dark:hover:bg-blue-950/30 dark:hover:text-blue-300'}`}>
    <Icon name={item.icon} size={17} class={active ? 'opacity-90' : 'text-slate-400'} />
    <span>{item.label}</span>
  </button>;
}
