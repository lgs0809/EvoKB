import type { ComponentChildren } from 'preact';

export type IconName =
  | 'dashboard' | 'search' | 'documents' | 'wiki' | 'graph' | 'evidence'
  | 'tasks' | 'admin' | 'refresh' | 'arrow' | 'check' | 'warning' | 'clock'
  | 'file' | 'layers' | 'shield' | 'sparkles' | 'download' | 'filter' | 'close'
  | 'chevron' | 'link' | 'database' | 'server' | 'activity' | 'menu';

export function Icon({ name, size = 18, class: className = '' }: { name: IconName; size?: number; class?: string }) {
  const paths: Record<IconName, ComponentChildren> = {
    dashboard: <><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></>,
    search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></>,
    documents: <><path d="M6 2h8l4 4v16H6z"/><path d="M14 2v5h5"/><path d="M9 12h6M9 16h6"/></>,
    wiki: <><path d="M4 5.5A3.5 3.5 0 0 1 7.5 2H20v17H7.5A3.5 3.5 0 0 0 4 22.5z"/><path d="M4 5.5v17"/><path d="M8 7h8M8 11h7"/></>,
    graph: <><circle cx="5" cy="12" r="2.5"/><circle cx="19" cy="5" r="2.5"/><circle cx="19" cy="19" r="2.5"/><path d="m7.3 10.9 9.3-4.7M7.3 13.1l9.3 4.7"/></>,
    evidence: <><path d="M12 3 4 6v5c0 5.2 3.4 8.5 8 10 4.6-1.5 8-4.8 8-10V6z"/><path d="m9 12 2 2 4-5"/></>,
    tasks: <><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h5M8 16h7"/><path d="m16 15 1.5 1.5L20 14"/></>,
    admin: <><circle cx="12" cy="8" r="4"/><path d="M5 21a7 7 0 0 1 14 0"/><path d="M18 4l2-2M4 4 2 2"/></>,
    refresh: <><path d="M20 6v5h-5"/><path d="M4 18v-5h5"/><path d="M18.7 9A7.5 7.5 0 0 0 6.2 5.2L4 7M5.3 15A7.5 7.5 0 0 0 17.8 18.8L20 17"/></>,
    arrow: <><path d="M5 12h14"/><path d="m15 8 4 4-4 4"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    warning: <><path d="M12 3 2.5 20h19z"/><path d="M12 9v4M12 17h.01"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    file: <><path d="M6 2h8l4 4v16H6z"/><path d="M14 2v5h5"/></>,
    layers: <><path d="m12 2 9 5-9 5-9-5z"/><path d="m3 12 9 5 9-5M3 17l9 5 9-5"/></>,
    shield: <><path d="M12 3 4 6v5c0 5.2 3.4 8.5 8 10 4.6-1.5 8-4.8 8-10V6z"/><path d="m9 12 2 2 4-5"/></>,
    sparkles: <><path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2z"/><path d="m19 14 .7 2.3L22 17l-2.3.7L19 20l-.7-2.3L16 17l2.3-.7z"/><path d="m5 14 .7 1.8L8 16.5l-2.3.7L5 19l-.7-1.8-2.3-.7 2.3-.7z"/></>,
    download: <><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/></>,
    filter: <path d="M3 5h18l-7 8v6l-4 2v-8z"/>,
    close: <path d="m6 6 12 12M18 6 6 18"/>,
    chevron: <path d="m9 18 6-6-6-6"/>,
    link: <><path d="M10 13a5 5 0 0 0 7.1.1l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1"/><path d="M14 11a5 5 0 0 0-7.1-.1l-2 2A5 5 0 0 0 12 20l1.1-1.1"/></>,
    database: <><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7"/></>,
    server: <><rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01"/></>,
    activity: <path d="M3 12h4l2-7 4 14 2-7h6"/>,
    menu: <path d="M4 7h16M4 12h16M4 17h16"/>,
  };
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" class={className} aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

export function StatusBadge({ value }: { value: string }) {
  const normalized = (value || 'UNKNOWN').toUpperCase();
  const good = ['ACTIVE', 'PASS', 'SUCCEEDED', 'RECOMMENDED', 'READY'].includes(normalized);
  const warn = ['PENDING', 'RUNNING', 'STALE', 'UNDER_REVIEW'].includes(normalized);
  const bad = ['FAILED', 'FAIL', 'INACTIVE', 'CANCELLED', 'CONFLICT'].includes(normalized);
  const tone = good
    ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/70 dark:bg-emerald-950/60 dark:text-emerald-300'
    : warn
      ? 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-900/70 dark:bg-amber-950/60 dark:text-amber-300'
      : bad
        ? 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-900/70 dark:bg-rose-950/60 dark:text-rose-300'
        : 'border-slate-200 bg-slate-50 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300';
  return <span class={`inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] font-semibold tracking-wide ${tone}`}><span class="h-1.5 w-1.5 rounded-full bg-current opacity-70" />{normalized}</span>;
}

export function MetricCard({ label, value, hint, icon, tone = 'blue' }: { label: string; value: string | number; hint?: string; icon: IconName; tone?: 'blue' | 'emerald' | 'amber' | 'violet' }) {
  const colors = {
    blue: 'bg-blue-50 text-blue-700 dark:bg-blue-950/50 dark:text-blue-300',
    emerald: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300',
    amber: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
    violet: 'bg-violet-50 text-violet-700 dark:bg-violet-950/50 dark:text-violet-300',
  }[tone];
  return <div class="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm shadow-slate-200/30 dark:border-slate-800 dark:bg-slate-900 dark:shadow-none">
    <div class="flex items-start justify-between gap-4">
      <div><div class="text-sm font-medium text-slate-500 dark:text-slate-400">{label}</div><div class="mt-2 text-3xl font-semibold tracking-tight text-slate-950 dark:text-white">{value}</div>{hint && <div class="mt-1 text-xs text-slate-400">{hint}</div>}</div>
      <div class={`rounded-xl p-2.5 ${colors}`}><Icon name={icon} size={20} /></div>
    </div>
  </div>;
}

export function SectionTitle({ eyebrow, title, description, action }: { eyebrow?: string; title: string; description?: string; action?: ComponentChildren }) {
  return <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
    <div>
      {eyebrow && <div class="mb-1 text-xs font-semibold uppercase tracking-[0.18em] text-blue-600 dark:text-blue-400">{eyebrow}</div>}
      <h2 class="text-xl font-semibold tracking-tight text-slate-950 dark:text-white">{title}</h2>
      {description && <p class="mt-1 max-w-3xl text-sm leading-6 text-slate-500 dark:text-slate-400">{description}</p>}
    </div>
    {action}
  </div>;
}

export function EmptyState({ icon = 'layers', title, description, action }: { icon?: IconName; title: string; description: string; action?: ComponentChildren }) {
  return <div class="flex min-h-52 flex-col items-center justify-center rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 px-6 py-10 text-center dark:border-slate-700 dark:bg-slate-900/50">
    <div class="mb-4 rounded-2xl bg-white p-3 text-slate-400 shadow-sm dark:bg-slate-800"><Icon name={icon} size={24} /></div>
    <div class="font-semibold text-slate-800 dark:text-slate-100">{title}</div>
    <p class="mt-1 max-w-md text-sm leading-6 text-slate-500 dark:text-slate-400">{description}</p>
    {action && <div class="mt-4">{action}</div>}
  </div>;
}

export function LoadingBlock({ rows = 4 }: { rows?: number }) {
  return <div class="space-y-3 animate-pulse">{Array.from({ length: rows }).map((_, i) => <div key={i} class="h-16 rounded-xl bg-slate-100 dark:bg-slate-800" />)}</div>;
}

export function formatNumber(value?: number) {
  return new Intl.NumberFormat('zh-CN').format(value || 0);
}

export function formatDate(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date);
}

export function shortID(value?: string, length = 8) {
  if (!value) return '—';
  return value.length <= length ? value : value.slice(0, length);
}

export function pct(numerator: number, denominator: number) {
  if (!denominator) return '—';
  return `${Math.round((numerator / denominator) * 100)}%`;
}

export function PrimaryButton({ children, onClick, disabled, icon }: { children: ComponentChildren; onClick?: () => void; disabled?: boolean; icon?: IconName }) {
  return <button onClick={onClick} disabled={disabled} class="inline-flex items-center justify-center gap-2 rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-white dark:text-slate-950 dark:hover:bg-slate-200">{icon && <Icon name={icon} size={16} />}{children}</button>;
}

export function SecondaryButton({ children, onClick, disabled, icon }: { children: ComponentChildren; onClick?: () => void; disabled?: boolean; icon?: IconName }) {
  return <button onClick={onClick} disabled={disabled} class="inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 shadow-sm transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800">{icon && <Icon name={icon} size={16} />}{children}</button>;
}

export function Pipeline({ summaryReady, wikiReady, graphReady }: { summaryReady: boolean; wikiReady: boolean; graphReady: boolean }) {
  const steps = [
    { label: 'Document', done: true },
    { label: 'Raw', done: true },
    { label: 'Summary', done: summaryReady },
    { label: 'Wiki', done: wikiReady },
    { label: 'Graph', done: graphReady },
    { label: 'Evidence', done: true },
  ];
  return <div class="flex flex-wrap items-center gap-2">{steps.map((step, i) => <div class="contents" key={step.label}>
    <div class={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium ${step.done ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300' : 'border-slate-200 bg-slate-50 text-slate-400 dark:border-slate-700 dark:bg-slate-800/70'}`}>
      {step.done ? <Icon name="check" size={13} /> : <Icon name="clock" size={13} />}{step.label}
    </div>
    {i < steps.length - 1 && <Icon name="arrow" size={14} class="text-slate-300 dark:text-slate-600" />}
  </div>)}</div>;
}
