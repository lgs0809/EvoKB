import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import type { EnterpriseUser } from '../lib/api';
import { Icon } from './ui';
import { DialogFrame } from './DialogFrame';

import { navigation, managementSections, sectionMeta, sectionPath, type NavigationItem, type EnterpriseSection } from './navigation';
export type { EnterpriseSection } from './navigation';

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
  const [managementOpen, setManagementOpen] = useState(() => managementSections.has(section));
  const [darkMode, setDarkMode] = useState(() => {
    const stored = localStorage.getItem('evokb-theme') ?? localStorage.getItem('enterprise-knowledge-base-theme');
    const initialDark = stored === 'dark' || (stored !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.toggle('dark', initialDark);
    return initialDark;
  });
  const meta = sectionMeta(section);
  const visibleItems = navigation.filter(item => !item.hidden && (!item.adminOnly || user.role === 'ADMIN'));
  const visibleAdminItems = visibleItems.filter(item => item.group === 'management');

  useEffect(() => {
    if (managementSections.has(section)) setManagementOpen(true);
  }, [section]);

  useEffect(() => {
    const dismiss = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setMobileOpen(false); setAccountOpen(false); }
    };
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Element && !event.target.closest('[data-account-menu]')) setAccountOpen(false);
    };
    document.addEventListener('keydown', dismiss);
    document.addEventListener('pointerdown', outside);
    return () => { document.removeEventListener('keydown', dismiss); document.removeEventListener('pointerdown', outside); };
  }, []);

  const navigate = (next: EnterpriseSection) => {
    onNavigate(next);
    setMobileOpen(false);
    window.requestAnimationFrame(() => document.getElementById('enterprise-main')?.focus({ preventScroll: true }));
  };

  const toggleTheme = () => {
    const nextDark = !darkMode;
    document.documentElement.classList.toggle('dark', nextDark);
    localStorage.setItem('evokb-theme', nextDark ? 'dark' : 'light');
    setDarkMode(nextDark);
  };

  const sidebar = <aside class={`fixed inset-y-0 left-0 z-40 flex w-[236px] flex-col border-r border-slate-200/80 bg-white transition-transform dark:border-slate-800 dark:bg-slate-900 lg:translate-x-0 ${mobileOpen ? 'visible translate-x-0' : 'invisible -translate-x-full lg:visible'}`} aria-label="主导航">
      <div class="flex h-20 items-center gap-3 border-b border-slate-100 px-5 dark:border-slate-800">
        <div class="flex h-10 w-10 items-center justify-center rounded-2xl bg-blue-600 text-white shadow-lg shadow-blue-600/20 dark:bg-blue-500"><Icon name="layers" size={21} /></div>
        <div class="min-w-0 flex-1"><div class="truncate text-sm font-semibold tracking-tight">EvoKB</div><div class="mt-0.5 text-[11px] text-slate-400">企业知识管理与查询</div></div>
        {mobileOpen && <button aria-label="关闭导航" onClick={() => setMobileOpen(false)} class="rounded-lg p-2 text-slate-500 lg:hidden"><Icon name="close" size={16}/></button>}
      </div>

      <nav class="flex-1 overflow-y-auto px-3 py-4" aria-label="页面导航">
        {(['workspace', 'knowledge'] as const).map(group => <div class="mb-6" key={group}>
          <div class="mb-2 px-3 text-[10px] font-semibold tracking-widest text-slate-400">{group === 'workspace' ? '工作台' : '知识资产'}</div>
          <div class="space-y-1">{visibleItems.filter(item => item.group === group).map(item => <NavigationButton key={item.id} item={item} active={item.id === section} onClick={() => navigate(item.id)} />)}</div>
        </div>)}
        {visibleAdminItems.length > 0 && <div class="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800">
          <button aria-expanded={managementOpen} aria-controls="management-navigation" onClick={() => setManagementOpen(value => !value)} class={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm font-medium transition ${managementSections.has(section) ? 'text-blue-700 dark:text-blue-300' : 'text-slate-500 hover:bg-slate-50 dark:text-slate-400 dark:hover:bg-slate-800/60'}`}>
            <Icon name="settings" size={17} class="text-slate-400" />
            <span class="flex-1">管理与维护</span>
            <Icon name="chevron" size={15} class={`transition-transform ${managementOpen ? 'rotate-90' : ''}`} />
          </button>
          {managementOpen && <div id="management-navigation" class="mt-1 space-y-1 pl-2">{visibleAdminItems.map(item => <NavigationButton key={`${item.id}-${item.label}`} item={item} active={item.id === section} onClick={() => navigate(item.id)} compact />)}</div>}
        </div>}
      </nav>
      <div class="border-t border-slate-100 p-4 dark:border-slate-800">
        <div class="flex items-start gap-2.5 rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-500 dark:bg-slate-950/50 dark:text-slate-400"><Icon name="shield" size={15} class="mt-0.5 shrink-0"/><span>以原文为依据<br/>按当前权限查看资料</span></div>
      </div>
    </aside>;

  return <div class="min-h-screen bg-[#f6f8fb] text-slate-900 dark:bg-slate-950 dark:text-slate-100">
    <a href="#enterprise-main" class="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[90] focus:rounded-xl focus:bg-blue-600 focus:px-4 focus:py-3 focus:text-white">跳到主要内容</a>
    {mobileOpen ? <DialogFrame title="主导航" side="left" onClose={() => setMobileOpen(false)}>{sidebar}</DialogFrame> : sidebar}

    <div class="lg:pl-[236px]">
      <header class="sticky top-0 z-20 border-b border-slate-200/70 bg-[#f6f8fb]/90 backdrop-blur-xl dark:border-slate-800 dark:bg-slate-950/85">
        <div class="flex min-h-20 items-center gap-4 px-4 sm:px-6 lg:px-8">
          <button class="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-600 shadow-sm lg:hidden dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300" onClick={() => setMobileOpen(value => !value)} aria-label={mobileOpen ? "关闭导航" : "打开导航"} aria-expanded={mobileOpen}><Icon name="menu" /></button>
          <div class="min-w-0 flex-1"><h1 class="truncate text-lg font-semibold tracking-tight text-slate-950 dark:text-white">{meta.title}</h1><p class="hidden truncate text-xs text-slate-500 sm:block dark:text-slate-400">{meta.description}</p></div>
          <div class="flex items-center gap-2">
            {onRefresh && <button onClick={onRefresh} disabled={busy} title="刷新当前页面" aria-label="刷新当前页面" class="flex h-10 w-10 items-center justify-center rounded-xl border border-blue-200 bg-blue-50/70 text-blue-600 shadow-sm transition hover:bg-blue-100 disabled:opacity-50 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60"><Icon name="refresh" class={busy ? 'animate-spin' : ''} /></button>}
            <button onClick={toggleTheme} title={darkMode ? '切换到浅色模式' : '切换到深色模式'} aria-label={darkMode ? '切换到浅色模式' : '切换到深色模式'} class="flex h-10 w-10 items-center justify-center rounded-xl border border-blue-200 bg-blue-50/70 text-blue-600 shadow-sm transition hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60"><Icon name={darkMode ? 'sun' : 'moon'} /></button>
            {section !== 'ask' && <button onClick={() => navigate('ask')} class="hidden rounded-xl border border-blue-200 bg-blue-50/70 px-3.5 py-2.5 text-sm font-medium text-blue-700 shadow-sm transition hover:border-blue-300 hover:bg-blue-100 sm:block dark:border-blue-900/70 dark:bg-blue-950/30 dark:text-blue-300 dark:hover:bg-blue-950/60">查知识</button>}
            <div class="relative">
              <button aria-label="账户菜单" aria-expanded={accountOpen} data-account-menu onClick={() => setAccountOpen(value => !value)} class="flex items-center gap-2 rounded-xl border border-blue-200 bg-blue-50/50 p-1.5 pr-2.5 shadow-sm transition hover:bg-blue-100 dark:border-blue-900/70 dark:bg-blue-950/20 dark:hover:bg-blue-950/50">
                <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600 text-xs font-semibold text-white dark:bg-blue-500">{(user.display_name || user.username).slice(0, 1).toUpperCase()}</div>
                <div class="hidden text-left md:block"><div class="max-w-28 truncate text-xs font-semibold text-slate-700 dark:text-slate-200">{user.display_name || user.username}</div><div class="text-[10px] tracking-wide text-slate-400">{user.role === 'ADMIN' ? '管理员' : '普通用户'}</div></div>
              </button>
              {accountOpen && <div data-account-menu class="absolute right-0 top-12 z-50 w-56 overflow-hidden rounded-2xl border border-slate-200 bg-white p-2 shadow-xl shadow-slate-200/50 dark:border-slate-700 dark:bg-slate-900 dark:shadow-none">
                <div class="border-b border-slate-100 px-3 py-2.5 dark:border-slate-800"><div class="truncate text-sm font-semibold">{user.display_name || user.username}</div><div class="mt-0.5 truncate text-xs text-slate-400">@{user.username} · {user.role === 'ADMIN' ? '管理员' : '普通用户'}</div></div>
                <button onClick={() => { setAccountOpen(false); onChangePassword(); }} class="mt-1 flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800"><Icon name="shield" size={15}/>修改密码</button>
                <button onClick={() => { setAccountOpen(false); onLogout(); }} class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-950/30"><Icon name="arrow" size={15}/>退出登录</button>
              </div>}
            </div>
          </div>
        </div>
      </header>

      {error && <div role="alert" class="mx-4 mt-4 flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700 sm:mx-6 lg:mx-8 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300"><Icon name="warning" size={18} class="mt-0.5 shrink-0" /><div class="break-words">{error}</div></div>}
      <main id="enterprise-main" tabIndex={-1} class="mx-auto max-w-[1500px] p-4 sm:p-6 lg:p-8">{children}</main>
    </div>
  </div>;
}

function NavigationButton({ item, active, onClick, compact = false }: { item: NavigationItem; active: boolean; onClick: () => void; compact?: boolean }) {
  return <a href={sectionPath(item.id)} aria-current={active ? 'page' : undefined} onClick={event => { if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return; event.preventDefault(); onClick(); }} class={`flex w-full items-center gap-3 rounded-xl ${compact ? 'px-3 py-2' : 'px-3 py-2.5'} text-left text-sm font-medium transition ${active ? 'bg-blue-50 text-blue-700 dark:bg-blue-950/50 dark:text-blue-300' : 'text-slate-600 hover:bg-blue-50 hover:text-blue-700 dark:text-slate-300 dark:hover:bg-blue-950/30 dark:hover:text-blue-300'}`}>
    <Icon name={item.icon} size={17} class={active ? 'opacity-90' : 'text-slate-400'} />
    <span>{item.label}</span>
  </a>;
}
