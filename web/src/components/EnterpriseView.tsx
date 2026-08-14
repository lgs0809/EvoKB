import { useEffect, useState } from 'preact/hooks';
import {
  fetchEnterpriseCapabilities,
  fetchEnterpriseDocuments,
  fetchEnterpriseEvidences,
  fetchEnterpriseOverview,
  fetchEnterpriseTasks,
  fetchEnterpriseWikis,
  type EnterpriseCapabilities,
  type EnterpriseDocumentVersion,
  type EnterpriseEvidence,
  type EnterpriseEvidenceListItem,
  type EnterpriseOverview,
  type EnterpriseTask,
  type EnterpriseWiki,
} from '../lib/api';
import { AdminPage } from '../enterprise/AdminPage';
import { DashboardPage } from '../enterprise/DashboardPage';
import { DocumentsPage } from '../enterprise/DocumentsPage';
import { EnterpriseShell, type EnterpriseSection } from '../enterprise/EnterpriseShell';
import { EvidencePage } from '../enterprise/EvidencePage';
import { GraphPage } from '../enterprise/GraphPage';
import { SearchPage } from '../enterprise/SearchPage';
import { TasksPage } from '../enterprise/TasksPage';
import { WikiPage } from '../enterprise/WikiPage';
import { Icon, SecondaryButton, shortID } from '../enterprise/ui';

const sections: EnterpriseSection[] = ['dashboard', 'search', 'documents', 'wiki', 'graph', 'evidence', 'tasks', 'admin'];

export function EnterpriseView() {
  const [section, setSection] = useState<EnterpriseSection>(() => sectionFromPath(window.location.pathname));
  const [overview, setOverview] = useState<EnterpriseOverview | null>(null);
  const [documents, setDocuments] = useState<EnterpriseDocumentVersion[]>([]);
  const [wikis, setWikis] = useState<EnterpriseWiki[]>([]);
  const [evidences, setEvidences] = useState<EnterpriseEvidenceListItem[]>([]);
  const [tasks, setTasks] = useState<EnterpriseTask[]>([]);
  const [capabilities, setCapabilities] = useState<EnterpriseCapabilities | null>(null);
  const [evidence, setEvidence] = useState<EnterpriseEvidence | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const loadAll = async () => {
    setBusy(true);
    try {
      const [overviewData, documentData, wikiData, evidenceData, taskData, capabilityData] = await Promise.all([
        fetchEnterpriseOverview(),
        fetchEnterpriseDocuments(),
        fetchEnterpriseWikis(),
        fetchEnterpriseEvidences(),
        fetchEnterpriseTasks(),
        fetchEnterpriseCapabilities(),
      ]);
      setOverview(overviewData);
      setDocuments(documentData.documents || []);
      setWikis(wikiData.wikis || []);
      setEvidences(evidenceData.evidences || []);
      setTasks(taskData.tasks || []);
      setCapabilities(capabilityData);
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
    setSection(next);
    const path = next === 'dashboard' ? '/enterprise' : `/enterprise/${next}`;
    if (window.location.pathname !== path) window.history.pushState({}, '', path);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  useEffect(() => { void loadAll(); }, []);
  useEffect(() => {
    const onPopState = () => setSection(sectionFromPath(window.location.pathname));
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  return <EnterpriseShell section={section} onNavigate={navigate} error={error} busy={busy} onRefresh={() => void loadAll()}>
    {section === 'dashboard' && <DashboardPage overview={overview} documents={documents} wikis={wikis} tasks={tasks} onNavigate={navigate}/>}
    {section === 'search' && <SearchPage documents={documents} onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'documents' && <DocumentsPage documents={documents} onReload={reloadDocuments} onNavigate={navigate} onError={setError}/>}
    {section === 'wiki' && <WikiPage wikis={wikis} onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'graph' && <GraphPage onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'evidence' && <EvidencePage evidences={evidences} overview={overview} onOpenEvidence={setEvidence} onError={setError}/>}
    {section === 'tasks' && <TasksPage tasks={tasks} overview={overview}/>}
    {section === 'admin' && <AdminPage capabilities={capabilities} overview={overview}/>}
    {evidence && <EvidenceDrawer evidence={evidence} onClose={() => setEvidence(null)}/>}
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
