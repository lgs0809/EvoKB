import type { EnterprisePageParams, EnterpriseDocumentVersion, EnterpriseDocumentDetail, EnterpriseEvidence, EnterpriseWiki, EnterpriseKnowledgeEntry, EnterpriseGraphRelation, EnterpriseEvidenceListItem } from './contracts';
import { BASE, authHeaders, enterpriseFetch, responseError, pageQuery } from './transport';

export async function fetchEnterpriseDocuments(params: EnterprisePageParams = {}): Promise<{ documents: EnterpriseDocumentVersion[]; total: number; page: number; page_size: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/documents?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Documents request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseDocumentDetail(versionId: string, blockLimit = 120): Promise<EnterpriseDocumentDetail> {
  const qs = new URLSearchParams({ version_id: versionId, block_limit: String(blockLimit) });
  const res = await enterpriseFetch(`${BASE}/api/enterprise/document/detail?${qs}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Document detail failed');
  return res.json();
}

export async function updateEnterpriseDocumentMetadata(versionId: string, input: {
  logical_name: string;
  source_department: string;
  material_category: string;
  document_type: string;
  document_date: string;
  manual_tags: string[];
}): Promise<EnterpriseDocumentVersion> {
  const qs = new URLSearchParams({ version_id: versionId });
  const res = await enterpriseFetch(`${BASE}/api/enterprise/document?${qs}`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Document metadata update failed');
  return res.json();
}

export async function fetchEnterpriseWikis(params: EnterprisePageParams = {}): Promise<{ wikis: EnterpriseWiki[]; total: number; page: number; page_size: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/wikis?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Wiki list request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseWiki(id: string, version?: number): Promise<EnterpriseWiki> {
  const qs = new URLSearchParams({ id });
  if (version) qs.set('version', String(version));
  const res = await enterpriseFetch(`${BASE}/api/enterprise/wiki?${qs}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Wiki request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseKnowledgeEntries(params: { page?: number; page_size?: number; q?: string; stage?: string } = {}): Promise<{ entries: EnterpriseKnowledgeEntry[]; total: number; page: number; page_size: number }> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== '') query.set(key, String(value));
  const response = await enterpriseFetch(`${BASE}/api/enterprise/knowledge-entries?${query}`, { headers: authHeaders() });
  if (!response.ok) throw await responseError(response, '暂时无法读取知识条目，请刷新后重试。');
  return response.json();
}

export async function fetchEnterpriseKnowledgeEntry(id: string): Promise<EnterpriseKnowledgeEntry> {
  const response = await enterpriseFetch(`${BASE}/api/enterprise/knowledge-entry?id=${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!response.ok) throw await responseError(response, '此条目暂不可用，资料版本或权限可能已变化。');
  return response.json();
}

export async function fetchEnterpriseEvidence(id: string): Promise<EnterpriseEvidence> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/evidence?id=${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Evidence request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseGraph(query: string, options: { depth?: 1 | 2; limit?: number; relation_type?: string; valid_at?: string } = {}): Promise<{ relations: EnterpriseGraphRelation[]; total: number }> {
  const qs = new URLSearchParams({ q: query, depth: String(options.depth || 2), limit: String(options.limit || 30) });
  if (options.relation_type?.trim()) qs.set('relation_type', options.relation_type.trim());
  if (options.valid_at?.trim()) qs.set('valid_at', options.valid_at.trim());
  const res = await enterpriseFetch(`${BASE}/api/enterprise/graph?${qs}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Graph request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseEvidences(params: EnterprisePageParams = {}): Promise<{ evidences: EnterpriseEvidenceListItem[]; total: number; page: number; page_size: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/evidences?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Evidence list request failed: ${res.status}`);
  return res.json();
}
