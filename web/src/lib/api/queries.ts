import type { EnterpriseSearchFilters, EnterpriseSearchResponse, EnterprisePublishedPackage, EnterpriseEvidenceSource, EnterpriseAskResponse, EnterpriseQueryResponse, EnterpriseQuerySessionSummary, EnterpriseQueryPackage, EnterpriseQueryHistoryRecord, EnterpriseQueryGroup } from './contracts';
import { BASE, authHeaders, enterpriseFetch, responseError } from './transport';

export async function searchEnterpriseKnowledge(
  query: string,
  filters: EnterpriseSearchFilters = {},
  limit = 8,
): Promise<EnterpriseSearchResponse> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/search`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, filters, limit }),
  });
  if (!res.ok) throw new Error(`Knowledge search failed: ${res.status}`);
  return res.json();
}

export async function packageEnterpriseSources(evidenceIds: string[], sessionId?: string): Promise<EnterprisePublishedPackage> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/package`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ evidence_ids: evidenceIds, session_id: sessionId }),
  });
  if (!res.ok) throw await responseError(res, '资料包暂时不可用，请检查资料权限后重试');
  return res.json();
}

export async function downloadEnterprisePackage(url: string): Promise<void> {
  const target = new URL(url, window.location.origin);
  if (target.origin !== window.location.origin || target.pathname !== '/api/enterprise/package/file') {
    throw new Error('资料包下载地址无效，请刷新后重新打包。');
  }
  const response = await enterpriseFetch(target.href, { method: 'HEAD', headers: authHeaders(), cache: 'no-store' });
  if (!response.ok) throw await responseError(response, '资料包已过期或访问权限发生变化，请刷新记录后重新打包。');
  window.location.assign(target.href);
}

export async function fetchEnterpriseEvidenceSource(evidenceId: string): Promise<EnterpriseEvidenceSource> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/evidence/source?id=${encodeURIComponent(evidenceId)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Evidence source request failed');
  return res.json();
}

export async function askEnterpriseKnowledge(question: string, filters: EnterpriseSearchFilters = {}, limit = 8): Promise<EnterpriseAskResponse> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/ask`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ question, filters, limit }) });
  if (!res.ok) throw await responseError(res, 'Enterprise Ask failed');
  return res.json();
}

export async function queryEnterpriseKnowledge(question: string, filters: EnterpriseSearchFilters = {}, limit = 8): Promise<EnterpriseQueryResponse> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ question, filters, limit }) });
  if (!res.ok) throw await responseError(res, 'Enterprise knowledge query failed');
  return res.json();
}

export async function continueEnterpriseQuery(sessionId: string, clarification: string, limit = 8): Promise<EnterpriseQueryResponse> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/continue`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ session_id: sessionId, clarification, limit }) });
  if (!res.ok) throw await responseError(res, 'Enterprise query continuation failed');
  return res.json();
}

export async function fetchEnterpriseQuerySession(sessionId: string): Promise<EnterpriseQueryResponse> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/session?id=${encodeURIComponent(sessionId)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Enterprise query session failed');
  return res.json();
}

export async function fetchEnterpriseQuerySessions(limit = 20): Promise<{ sessions: EnterpriseQuerySessionSummary[]; total: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/sessions?limit=${encodeURIComponent(String(limit))}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Enterprise query history failed');
  return res.json();
}

export async function fetchEnterpriseQueryHistory(limit = 100): Promise<{ records: EnterpriseQueryHistoryRecord[]; total: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/history?limit=${encodeURIComponent(String(limit))}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query record list failed');
  return res.json();
}

export async function renameEnterpriseQueryRecord(sessionId: string, name: string): Promise<EnterpriseQueryHistoryRecord> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/history/record?id=${encodeURIComponent(sessionId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
  if (!res.ok) throw await responseError(res, 'Query record rename failed');
  return res.json();
}

export async function moveEnterpriseQueryRecord(sessionId: string, groupId?: string): Promise<EnterpriseQueryHistoryRecord> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/history/record/group?id=${encodeURIComponent(sessionId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ group_id: groupId || null }) });
  if (!res.ok) throw await responseError(res, 'Query record group update failed');
  return res.json();
}

export async function deleteEnterpriseQueryRecord(sessionId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/history/record?id=${encodeURIComponent(sessionId)}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query record delete failed');
}

export async function packageEnterpriseQueryRecord(sessionId: string, regenerate = false): Promise<EnterpriseQueryPackage> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/history/record/package`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ session_id: sessionId, regenerate }) });
  if (!res.ok) throw await responseError(res, 'Query record package failed');
  return res.json();
}

export async function fetchEnterpriseQueryGroups(): Promise<{ groups: EnterpriseQueryGroup[]; total: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/groups`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query group list failed');
  return res.json();
}

export async function createEnterpriseQueryGroup(name: string, sessionIds: string[]): Promise<EnterpriseQueryGroup> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/groups`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name, session_ids: sessionIds }) });
  if (!res.ok) throw await responseError(res, 'Query group creation failed');
  return res.json();
}

export async function renameEnterpriseQueryGroup(groupId: string, name: string): Promise<EnterpriseQueryGroup> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/group?id=${encodeURIComponent(groupId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
  if (!res.ok) throw await responseError(res, 'Query group rename failed');
  return res.json();
}

export async function dissolveEnterpriseQueryGroup(groupId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/group/dissolve`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ group_id: groupId }) });
  if (!res.ok) throw await responseError(res, 'Query group dissolve failed');
}

export async function deleteEnterpriseQueryGroup(groupId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/group?id=${encodeURIComponent(groupId)}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query group delete failed');
}

export async function packageEnterpriseQueryGroup(groupId: string, regenerate = false): Promise<EnterpriseQueryPackage> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/group/package`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ group_id: groupId, regenerate }) });
  if (!res.ok) throw await responseError(res, 'Query group package failed');
  return res.json();
}

export async function submitEnterpriseQueryFeedback(sessionId: string, rating: 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL', category = '', comment = ''): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/query/feedback`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ session_id: sessionId, rating, category, comment }) });
  if (!res.ok) throw await responseError(res, 'Enterprise query feedback failed');
}
