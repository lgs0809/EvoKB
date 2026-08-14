import { useEffect, useState } from 'preact/hooks';
import {
  fetchEnterpriseDocuments,
  fetchEnterpriseEvidence,
  fetchEnterpriseGraph,
  fetchEnterpriseWiki,
  fetchEnterpriseWikis,
  recommendEnterpriseDistill,
  type EnterpriseDocumentVersion,
  type EnterpriseEvidence,
  type EnterpriseGraphRelation,
  type EnterpriseWiki,
} from '../lib/api';

type Tab = 'documents' | 'wiki' | 'graph';

export function EnterpriseView() {
  const [tab, setTab] = useState<Tab>('documents');
  const [documents, setDocuments] = useState<EnterpriseDocumentVersion[]>([]);
  const [wikis, setWikis] = useState<EnterpriseWiki[]>([]);
  const [wiki, setWiki] = useState<EnterpriseWiki | null>(null);
  const [graphQuery, setGraphQuery] = useState('');
  const [relations, setRelations] = useState<EnterpriseGraphRelation[]>([]);
  const [evidence, setEvidence] = useState<EnterpriseEvidence | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const loadDocuments = async () => {
    try {
      setError('');
      const data = await fetchEnterpriseDocuments();
      setDocuments(data.documents || []);
    } catch (err) {
      setError(String(err));
    }
  };

  const loadWikis = async () => {
    try {
      setError('');
      const data = await fetchEnterpriseWikis();
      setWikis(data.wikis || []);
    } catch (err) {
      setError(String(err));
    }
  };

  useEffect(() => { void loadDocuments(); }, []);
  useEffect(() => { if (tab === 'wiki') void loadWikis(); }, [tab]);

  const openEvidence = async (id: string) => {
    setBusy(true);
    try {
      setEvidence(await fetchEnterpriseEvidence(id));
      setError('');
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const markDistill = async (versionId: string) => {
    setBusy(true);
    try {
      await recommendEnterpriseDistill(versionId);
      await loadDocuments();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const openWiki = async (item: EnterpriseWiki) => {
    setBusy(true);
    try {
      setWiki(await fetchEnterpriseWiki(item.wiki_id, item.wiki_version));
      setError('');
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  const searchGraph = async () => {
    if (!graphQuery.trim()) return;
    setBusy(true);
    try {
      const data = await fetchEnterpriseGraph(graphQuery);
      setRelations(data.relations || []);
      setError('');
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div class="min-h-screen bg-gray-50 dark:bg-gray-950 text-gray-900 dark:text-gray-100">
      <header class="sticky top-0 z-20 border-b border-gray-200 dark:border-gray-800 bg-white/95 dark:bg-gray-900/95 backdrop-blur">
        <div class="max-w-7xl mx-auto px-5 py-4 flex items-center justify-between gap-4">
          <div>
            <div class="text-xs uppercase tracking-[0.2em] text-gray-500">Enterprise Knowledge Base</div>
            <h1 class="text-xl font-semibold">恒安嘉新知识证据工作台</h1>
          </div>
          <a href="/wiki/" class="text-sm text-blue-600 dark:text-blue-400 hover:underline">返回 Sage-Wiki</a>
        </div>
        <nav class="max-w-7xl mx-auto px-5 flex gap-2 pb-3">
          {(['documents', 'wiki', 'graph'] as Tab[]).map(item => (
            <button
              key={item}
              onClick={() => setTab(item)}
              class={`px-3 py-1.5 rounded-md text-sm ${tab === item ? 'bg-gray-900 text-white dark:bg-white dark:text-gray-900' : 'bg-gray-100 dark:bg-gray-800'}`}
            >
              {item === 'documents' ? 'Documents' : item === 'wiki' ? 'Wiki Evidence' : 'Graph Evidence'}
            </button>
          ))}
        </nav>
      </header>

      <main class="max-w-7xl mx-auto p-5">
        {error && <div class="mb-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-200">{error}</div>}

        {tab === 'documents' && (
          <section class="space-y-3">
            <div class="flex items-center justify-between">
              <div><h2 class="font-semibold">Document / Version</h2><p class="text-sm text-gray-500">Raw 生命周期、metadata、Summary Gate 与 Distillation 推荐。</p></div>
              <button class="px-3 py-1.5 rounded border dark:border-gray-700 text-sm" onClick={() => void loadDocuments()}>刷新</button>
            </div>
            <div class="overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900">
              <table class="w-full text-sm">
                <thead class="bg-gray-50 dark:bg-gray-800 text-left text-xs uppercase text-gray-500"><tr>
                  <th class="p-3">文件 / Version</th><th class="p-3">Metadata</th><th class="p-3">Raw</th><th class="p-3">Summary</th><th class="p-3">Effective Hit</th><th class="p-3">Distill</th>
                </tr></thead>
                <tbody>
                  {documents.map(doc => (
                    <tr key={doc.version_id} class="border-t border-gray-100 dark:border-gray-800 align-top">
                      <td class="p-3"><div class="font-medium">{doc.logical_name}</div><div class="text-xs text-gray-500">v{doc.version_no} · {doc.version_id}</div></td>
                      <td class="p-3"><div>{doc.company || '—'}</div><div class="text-xs text-gray-500">{doc.document_type || '未分类'} · {doc.source_department || '—'}</div></td>
                      <td class="p-3"><Status value={doc.lifecycle} /></td>
                      <td class="p-3"><div>{doc.summary_count} summary</div><div class="text-xs text-gray-500">Grounding {doc.grounded_summaries} / Coverage {doc.covered_summaries}</div></td>
                      <td class="p-3">{doc.effective_hits}</td>
                      <td class="p-3">
                        {doc.recommendation === 'RECOMMENDED' ? <div><Status value="RECOMMENDED"/><div class="text-xs text-gray-500 mt-1">{doc.recommendation_reason}</div></div> : doc.lifecycle === 'ACTIVE' ? (
                          <button disabled={busy} class="px-2 py-1 rounded bg-blue-600 text-white disabled:opacity-50" onClick={() => void markDistill(doc.version_id)}>标记高价值</button>
                        ) : <span class="text-gray-400">—</span>}
                      </td>
                    </tr>
                  ))}
                  {documents.length === 0 && <tr><td class="p-6 text-center text-gray-500" colSpan={6}>暂无 Document Version</td></tr>}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {tab === 'wiki' && (
          <section class="grid lg:grid-cols-[320px_1fr] gap-4">
            <div class="rounded-lg border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 p-3">
              <h2 class="font-semibold mb-3">Wiki Versions</h2>
              <div class="space-y-2">
                {wikis.map(item => <button key={`${item.wiki_id}:${item.wiki_version}`} onClick={() => void openWiki(item)} class="w-full text-left p-2 rounded hover:bg-gray-50 dark:hover:bg-gray-800">
                  <div class="font-medium">{item.title}</div><div class="text-xs text-gray-500">{item.wiki_type} · v{item.wiki_version} · {item.lifecycle}</div>
                </button>)}
                {wikis.length === 0 && <div class="text-sm text-gray-500">暂无 Wiki。等待外部 LLM API 后可继续真实编译。</div>}
              </div>
            </div>
            <div class="rounded-lg border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 p-4">
              {!wiki ? <div class="text-gray-500">选择 Wiki 查看 Claim → Fact → Raw Evidence。</div> : <>
                <div class="mb-4"><h2 class="text-lg font-semibold">{wiki.title}</h2><div class="text-xs text-gray-500">{wiki.wiki_id} · v{wiki.wiki_version} · {wiki.lifecycle}</div></div>
                <div class="space-y-3">{(wiki.claims || []).map(claim => <article key={claim.claim_id} class="rounded border border-gray-200 dark:border-gray-800 p-3">
                  <div class="text-xs font-semibold uppercase text-gray-500 mb-1">{claim.section}</div><div>{claim.claim_text}</div>
                  <div class="mt-2 text-xs text-gray-500">Grounding {claim.grounding_status} · Temporal {claim.temporal_status}</div>
                  <div class="mt-2 flex flex-wrap gap-1">{(claim.evidence_ids || []).map(id => <EvidenceButton id={id} onOpen={openEvidence} />)}</div>
                </article>)}</div>
              </>}
            </div>
          </section>
        )}

        {tab === 'graph' && (
          <section class="space-y-4">
            <div class="flex gap-2"><input value={graphQuery} onInput={e => setGraphQuery((e.target as HTMLInputElement).value)} onKeyDown={e => { if (e.key === 'Enter') void searchGraph(); }} placeholder="输入公司、人员、合同或项目名称" class="flex-1 px-3 py-2 rounded border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-900"/><button onClick={() => void searchGraph()} disabled={busy} class="px-4 rounded bg-blue-600 text-white disabled:opacity-50">查询 1~2 hop</button></div>
            <div class="space-y-3">{relations.map(rel => <article key={rel.relation_id} class="rounded-lg border border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 p-4">
              <div class="font-medium">{rel.source_name} <span class="text-blue-600 dark:text-blue-400">— {rel.relation_type} →</span> {rel.target_name}</div>
              <div class="text-xs text-gray-500 mt-1">relation_id {rel.relation_id} · score {rel.score.toFixed(4)}</div>
              <div class="mt-3 flex flex-wrap gap-1">{(rel.evidence_ids || []).map(id => <EvidenceButton id={id} onOpen={openEvidence} />)}</div>
            </article>)}{relations.length === 0 && <div class="text-sm text-gray-500">输入实体关键词查看有 ACTIVE Evidence Support 的关系。</div>}</div>
          </section>
        )}
      </main>

      {evidence && <EvidenceDrawer evidence={evidence} onClose={() => setEvidence(null)} />}
    </div>
  );
}

function Status({ value }: { value: string }) {
  return <span class="inline-flex rounded-full border border-gray-300 dark:border-gray-700 px-2 py-0.5 text-xs font-medium">{value}</span>;
}

function EvidenceButton({ id, onOpen }: { id: string; onOpen: (id: string) => void }) {
  return <button key={id} onClick={() => void onOpen(id)} class="rounded bg-gray-100 dark:bg-gray-800 px-2 py-1 text-xs text-blue-700 dark:text-blue-300">Evidence {id.slice(0, 8)}</button>;
}

function EvidenceDrawer({ evidence, onClose }: { evidence: EnterpriseEvidence; onClose: () => void }) {
  return <div class="fixed inset-0 z-50 bg-black/35 flex justify-end" onClick={onClose}>
    <aside class="h-full w-full max-w-2xl overflow-y-auto bg-white dark:bg-gray-900 shadow-xl p-5" onClick={e => e.stopPropagation()}>
      <div class="flex items-start justify-between gap-3"><div><div class="text-xs uppercase text-gray-500">Raw Evidence</div><h2 class="font-semibold break-all">{evidence.evidence_id}</h2></div><button onClick={onClose} class="text-xl">×</button></div>
      <dl class="grid grid-cols-[120px_1fr] gap-x-3 gap-y-2 mt-5 text-sm"><dt class="text-gray-500">Document</dt><dd class="break-all">{evidence.document_id}</dd><dt class="text-gray-500">Version</dt><dd class="break-all">{evidence.version_id}</dd><dt class="text-gray-500">Object</dt><dd class="break-all">{evidence.object_key}</dd><dt class="text-gray-500">Location</dt><dd><pre class="whitespace-pre-wrap text-xs">{JSON.stringify(evidence.raw_location, null, 2)}</pre></dd></dl>
      <div class="mt-5"><div class="text-sm font-semibold mb-2">原始内容</div><pre class="whitespace-pre-wrap break-words rounded bg-gray-50 dark:bg-gray-950 border border-gray-200 dark:border-gray-800 p-3 text-sm leading-6">{evidence.raw_content}</pre></div>
    </aside>
  </div>;
}
