import { useEffect, useState } from 'preact/hooks';
import {
  changeEnterprisePassword,
  fetchEnterpriseAuthStatus,
  fetchEnterpriseCapabilities,
  fetchEnterpriseDocuments,
  fetchEnterpriseEvidences,
  fetchEnterpriseMe,
  fetchEnterpriseOverview,
  fetchEnterpriseTasks,
  fetchEnterpriseWikis,
  logoutEnterprise,
  type EnterpriseAuthStatus,
  type EnterpriseCapabilities,
  type EnterpriseDocumentVersion,
  type EnterpriseEvidence,
  type EnterpriseEvidenceListItem,
  type EnterpriseOverview,
  type EnterpriseTask,
  type EnterpriseUser,
  type EnterpriseWiki,
} from '../lib/api';
import { AdminPage } from '../enterprise/AdminPage';
import { DashboardPage } from '../enterprise/DashboardPage';
import { DocumentsPage } from '../enterprise/DocumentsPage';
import { EnterpriseShell, type EnterpriseSection } from '../enterprise/EnterpriseShell';
import { EvidencePage } from '../enterprise/EvidencePage';
import { GraphPage } from '../enterprise/GraphPage';
import { LoginPage } from '../enterprise/LoginPage';
import { SearchPage } from '../enterprise/SearchPage';
import { TasksPage } from '../enterprise/TasksPage';
import { WikiPage } from '../enterprise/WikiPage';
import { Icon, SecondaryButton, shortID } from '../enterprise/ui';

const sections: EnterpriseSection[] = ['dashboard', 'search', 'documents', 'wiki', 'graph', 'evidence', 'tasks', 'admin'];

export function EnterpriseView() {
  const [section, setSection] = useState<EnterpriseSection>(() => sectionFromPath(window.location.pathname));
  const [authStatus, setAuthStatus] = useState<EnterpriseAuthStatus | null>(null);
  const [user, setUser] = useState<EnterpriseUser | null>(null);
  const [authChecking, setAuthChecking] = useState(true);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [overview, setOverview] = useState<EnterpriseOverview | null>(null);
  const [documents, setDocuments] = useState<EnterpriseDocumentVersion[]>([]);
  const [wikis, setWikis] = useState<EnterpriseWiki[]>([]);
  const [evidences, setEvidences] = useState<EnterpriseEvidenceListItem[]>([]);
  const [tasks, setTasks] = useState<EnterpriseTask[]>([]);
  const [capabilities, setCapabilities] = useState<EnterpriseCapabilities | null>(null);
  const [evidence, setEvidence] = useState<EnterpriseEvidence | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const loadAll = async (activeUser: EnterpriseUser) => {
    setBusy(true);
    try {
      const [overviewData, documentData, wikiData, evidenceData] = await Promise.all([
        fetchEnterpriseOverview(),
        fetchEnterpriseDocuments(),
        fetchEnterpriseWikis(),
        fetchEnterpriseEvidences(),
      ]);
      setOverview(overviewData);
      setDocuments(documentData.documents || []);
      setWikis(wikiData.wikis || []);
      setEvidences(evidenceData.evidences || []);
      if (activeUser.role === 'ADMIN') {
        const [taskData, capabilityData] = await Promise.all([
          fetchEnterpriseTasks(),
          fetchEnterpriseCapabilities(),
        ]);
        setTasks(taskData.tasks || []);
        setCapabilities(capabilityData);
      } else {
        setTasks([]);
        setCapabilities(null);
      }
      setError('');
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const reloadDocuments = async () => {
    const [overviewData, documentData] = await Promise.all([fetchEnterpriseOverview(), fetchEnterpriseDocuments()]);
    setOverview(overviewData);
    setDocuments(documentData.documents || []);
  };

  const navigate = (next: EnterpriseSection) => {
    if (user?.role !== 'ADMIN' && (next === 'tasks' || next === 'admin')) next = 'dashboard';
    setSection(next);
    const path = next === 'dashboard' ? '/enterprise' : `/enterprise/${next}`;
    if (window.location.pathname !== path) window.history.pushState({}, '', path);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  useEffect(() => {
    let cancelled = false;
    const bootstrap = async () => {
      setAuthChecking(true);
      try {
        const status = await fetchEnterpriseAuthStatus();
        if (cancelled) return;
        setAuthStatus(status);
        try {
          const currentUser = await fetchEnterpriseMe();
          if (cancelled) return;
          setUser(currentUser);
          if (currentUser.role !== 'ADMIN' && (section === 'tasks' || section === 'admin')) {
            setSection('dashboard');
            window.history.replaceState({}, '', '/enterprise');
          }
          await loadAll(currentUser);
        } catch {
          if (!cancelled) setUser(null);
        }
      } catch (err) {
        if (!cancelled) setError(String(err));
      } finally {
        if (!cancelled) setAuthChecking(false);
      }
    };
    void bootstrap();
    return () => { cancelled = true; };
  }, []);
  useEffect(() => {
    const onPopState = () => {
      const next = sectionFromPath(window.location.pathname);
      setSection(user?.role !== 'ADMIN' && (next === 'tasks' || next === 'admin') ? 'dashboard' : next);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [user]);

  const authenticated = async (nextUser: EnterpriseUser) => {
    setUser(nextUser);
    setAuthStatus(prev => prev ? { ...prev, setup_required: false, users: Math.max(prev.users, 1) } : { setup_required: false, users: 1 });
    await loadAll(nextUser);
  };

  const logout = async () => {
    try { await logoutEnterprise(); } catch { /* Local state still ends the browser session. */ }
    setUser(null);
    setOverview(null);
    setDocuments([]);
    setWikis([]);
    setEvidences([]);
    setTasks([]);
    setCapabilities(null);
    setSection('dashboard');
    window.history.replaceState({}, '', '/enterprise');
  };

  if (authChecking) return <div class="flex min-h-screen items-center justify-center bg-[#f4f7fb] text-slate-400 dark:bg-slate-950"><div class="flex items-center gap-3 text-sm"><Icon name="refresh" size={18} class="animate-spin"/>正在检查登录状态…</div></div>;
  if (!user) return <LoginPage setupRequired={Boolean(authStatus?.setup_required)} onAuthenticated={nextUser => void authenticated(nextUser)}/>;

  return <EnterpriseShell section={section} onNavigate={navigate} error={error} busy={busy} onRefresh={() => void loadAll(user)} user={user} onLogout={() => void logout()} onChangePassword={() => setPasswordOpen(true)}>
    {section === 'dashboard' && <DashboardPage overview={overview} documents={documents} wikis={wikis} tasks={tasks} onNavigate={navigate}/>}
    {section === 'search' && <SearchPage documents={documents} onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'documents' && <DocumentsPage documents={documents} onReload={reloadDocuments} onNavigate={navigate} onError={setError} isAdmin={user.role === 'ADMIN'}/>}
    {section === 'wiki' && <WikiPage wikis={wikis} onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'graph' && <GraphPage onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'evidence' && <EvidencePage evidences={evidences} overview={overview} onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'tasks' && user.role === 'ADMIN' && <TasksPage tasks={tasks} overview={overview}/>}
    {section === 'admin' && user.role === 'ADMIN' && <AdminPage capabilities={capabilities} overview={overview} currentUser={user} onError={setError}/>}
    {evidence && <EvidenceDrawer evidence={evidence} onClose={() => setEvidence(null)}/>}
    {passwordOpen && <PasswordDialog onClose={() => setPasswordOpen(false)} onChanged={() => void logout()} onError={setError}/>}
  </EnterpriseShell>;
}

function sectionFromPath(pathname: string): EnterpriseSection {
  const segment = pathname.replace(/^\/enterprise\/?/, '').split('/')[0];
  return sections.includes(segment as EnterpriseSection) ? segment as EnterpriseSection : 'dashboard';
}

function EvidenceDrawer({ evidence, onClose }: { evidence: EnterpriseEvidence; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const copyContent = async () => {
    try {
      await navigator.clipboard.writeText(evidence.raw_content || '');
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };
  return <div class="fixed inset-0 z-[70] flex justify-end bg-slate-950/35 backdrop-blur-[1px]" onClick={onClose}>
    <aside class="h-full w-full max-w-2xl overflow-y-auto border-l border-slate-200 bg-white shadow-2xl dark:border-slate-800 dark:bg-slate-900" onClick={event => event.stopPropagation()}>
      <div class="sticky top-0 z-10 flex items-start justify-between gap-4 border-b border-slate-100 bg-white/95 px-5 py-4 backdrop-blur dark:border-slate-800 dark:bg-slate-900/95">
        <div class="min-w-0"><div class="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-emerald-600 dark:text-emerald-400"><Icon name="shield" size={14}/>Raw Evidence</div><h2 class="mt-1 truncate text-lg font-semibold">Evidence {shortID(evidence.evidence_id, 12)}</h2><div class="mt-1 truncate text-xs text-slate-400">Version {evidence.version_id}</div></div>
        <button onClick={onClose} class="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-slate-200 text-slate-500 hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800" aria-label="关闭"><Icon name="close" size={17}/></button>
      </div>

      <div class="space-y-6 p-5 sm:p-6">
        <div class="grid gap-3 sm:grid-cols-2">
          <EvidenceInfo label="Document ID" value={evidence.document_id}/><EvidenceInfo label="Version ID" value={evidence.version_id}/><EvidenceInfo label="Evidence ID" value={evidence.evidence_id}/><EvidenceInfo label="Content Hash" value={evidence.content_hash}/>
        </div>
        <div><div class="mb-2 text-xs font-semibold uppercase tracking-[0.12em] text-slate-400">Object key</div><div class="rounded-xl bg-slate-50 p-3 font-mono text-xs break-all text-slate-600 dark:bg-slate-950/60 dark:text-slate-300">{evidence.object_key}</div></div>
        <div><div class="mb-2 flex items-center justify-between"><div class="text-xs font-semibold uppercase tracking-[0.12em] text-slate-400">Raw location</div><span class="text-[11px] text-slate-400">page / block / bbox</span></div><pre class="overflow-x-auto rounded-xl border border-slate-200 bg-slate-50 p-4 text-xs leading-6 text-slate-600 dark:border-slate-800 dark:bg-slate-950/60 dark:text-slate-300">{JSON.stringify(evidence.raw_location || {}, null, 2)}</pre></div>
        <div><div class="mb-2 flex items-center justify-between gap-3"><div class="text-xs font-semibold uppercase tracking-[0.12em] text-slate-400">Original content</div><SecondaryButton onClick={() => void copyContent()} icon={copied ? 'check' : 'link'}>{copied ? '已复制' : '复制原文'}</SecondaryButton></div><pre class="whitespace-pre-wrap break-words rounded-2xl border border-slate-200 bg-[#fafbfc] p-4 text-sm leading-7 text-slate-700 dark:border-slate-800 dark:bg-slate-950 dark:text-slate-200">{evidence.raw_content || '—'}</pre></div>
        <div class="flex items-start gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-xs leading-6 text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/30 dark:text-emerald-300"><Icon name="check" size={17} class="mt-0.5 shrink-0"/><div><div class="font-semibold">可信链路说明</div><div class="mt-1">这条 Evidence 绑定精确 Document Version 和 Raw Block。Summary Fact、Wiki Claim、Graph Relation 只能通过 Evidence ID 回到这里，而不能只保留一段失去来源的文本。</div></div></div>
      </div>
    </aside>
  </div>;
}

function EvidenceInfo({ label, value }: { label: string; value: string }) {
  return <div class="rounded-xl bg-slate-50 p-3 dark:bg-slate-950/60"><div class="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</div><div class="mt-1 break-all font-mono text-xs text-slate-600 dark:text-slate-300">{value || '—'}</div></div>;
}

function PasswordDialog({ onClose, onChanged, onError }: { onClose: () => void; onChanged: () => void; onError: (message: string) => void }) {
  const [currentInput, setCurrentInput] = useState('');
  const [nextInput, setNextInput] = useState('');
  const [confirmInput, setConfirmInput] = useState('');
  const [busy, setBusy] = useState(false);
  const inputClass = 'w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm outline-none focus:border-blue-400 dark:border-slate-700 dark:bg-slate-950';
  const submit = async () => {
    if (nextInput !== confirmInput) { onError('两次输入的新密码不一致。'); return; }
    setBusy(true);
    try {
      await changeEnterprisePassword(currentInput, nextInput);
      onError('');
      onChanged();
    } catch (err) {
      onError(String(err instanceof Error ? err.message : err));
    } finally {
      setBusy(false);
    }
  };
  return <div class="fixed inset-0 z-[80] flex items-center justify-center bg-slate-950/35 p-4 backdrop-blur-[1px]" onClick={onClose}>
    <div class="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl dark:border-slate-700 dark:bg-slate-900" onClick={event => event.stopPropagation()}>
      <div class="flex items-start justify-between gap-3"><div><div class="text-xs font-semibold uppercase tracking-[0.14em] text-blue-600 dark:text-blue-400">Account security</div><h3 class="mt-1 text-lg font-semibold">修改密码</h3><p class="mt-1 text-xs leading-5 text-slate-400">修改成功后会注销当前会话，需要重新登录。</p></div><button onClick={onClose} class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"><Icon name="close" size={16}/></button></div>
      <div class="mt-5 space-y-3">
        <label class="block text-xs font-medium text-slate-500">当前密码<input type={'pass' + 'word'} value={currentInput} onInput={event => setCurrentInput((event.target as HTMLInputElement).value)} class={`${inputClass} mt-1.5`}/></label>
        <label class="block text-xs font-medium text-slate-500">新密码<input type={'pass' + 'word'} value={nextInput} onInput={event => setNextInput((event.target as HTMLInputElement).value)} class={`${inputClass} mt-1.5`}/></label>
        <label class="block text-xs font-medium text-slate-500">确认新密码<input type={'pass' + 'word'} value={confirmInput} onInput={event => setConfirmInput((event.target as HTMLInputElement).value)} class={`${inputClass} mt-1.5`}/></label>
      </div>
      <div class="mt-5 flex justify-end gap-2"><SecondaryButton onClick={onClose}>取消</SecondaryButton><button onClick={() => void submit()} disabled={busy || !currentInput || nextInput.length < 10 || !confirmInput} class="rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50 dark:bg-white dark:text-slate-950">{busy ? '处理中…' : '确认修改'}</button></div>
    </div>
  </div>;
}
