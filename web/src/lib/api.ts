import { authHeaders } from './token';

const BASE = '';

export interface FileEntry {
  name: string;
  path: string;
}

export interface TreeData {
  concepts: FileEntry[];
  summaries: FileEntry[];
  outputs: FileEntry[];
  stats: { concepts: number; summaries: number };
}

export interface Article {
  path: string;
  frontmatter: Record<string, string>;
  body: string;
}

export interface SearchHit {
  id: string;
  path: string;
  snippet: string;
  score: number;
}

export interface GraphData {
  nodes: { id: string; type: string; name: string; connections: number }[];
  edges: { source: string; target: string; relation: string }[];
  total: number;
  // Present on neighborhood queries: the entity the graph is actually
  // centered on. May differ from the requested ?center= when the server
  // resolved an alias to its canonical entity.
  center?: string;
}

export interface StatusData {
  project: string;
  entries: number;
  vectors: number;
  dimensions: number;
  entities: number;
  relations: number;
}

export async function fetchTree(): Promise<TreeData> {
  const res = await fetch(`${BASE}/api/tree`, { headers: authHeaders() });
  return res.json();
}

export async function fetchArticle(path: string): Promise<Article> {
  const res = await fetch(`${BASE}/api/articles/${path}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Article not found: ${path}`);
  return res.json();
}

export async function fetchSearch(query: string, limit = 10): Promise<{ results: SearchHit[]; total: number }> {
  const res = await fetch(`${BASE}/api/search?q=${encodeURIComponent(query)}&limit=${limit}`, { headers: authHeaders() });
  return res.json();
}

export async function fetchGraph(center?: string, depth = 2): Promise<GraphData> {
  let url = `${BASE}/api/graph`;
  if (center) url += `?center=${encodeURIComponent(center)}&depth=${depth}`;
  const res = await fetch(url, { headers: authHeaders() });
  return res.json();
}

export async function fetchStatus(): Promise<StatusData> {
  const res = await fetch(`${BASE}/api/status`, { headers: authHeaders() });
  return res.json();
}

export interface EnterpriseDocumentVersion {
  document_id: string;
  logical_name: string;
  version_id: string;
  version_no: number;
  lifecycle: string;
  original_filename: string;
  source_department?: string;
  material_category?: string;
  company?: string;
  document_type?: string;
  document_date?: string;
  parsed_at?: string;
  summary_count: number;
  grounded_summaries: number;
  covered_summaries: number;
  recommendation?: string;
  recommendation_reason?: string;
  effective_hits: number;
}

export interface EnterpriseEvidence {
  evidence_id: string;
  document_id: string;
  version_id: string;
  object_key: string;
  raw_location: Record<string, unknown>;
  raw_content: string;
  content_hash: string;
}

export interface EnterpriseClaim {
  claim_id: string;
  section: string;
  claim_text: string;
  grounding_status: string;
  temporal_status: string;
  fact_ids: string[];
  evidence_ids: string[];
}

export interface EnterpriseWiki {
  wiki_id: string;
  wiki_version: number;
  wiki_type: string;
  canonical_subject_id?: string;
  title: string;
  lifecycle: string;
  claims?: EnterpriseClaim[];
}

export interface EnterpriseGraphRelation {
  relation_id: string;
  source_entity_id: string;
  source_name: string;
  target_entity_id: string;
  target_name: string;
  relation_type: string;
  evidence_ids: string[];
  score: number;
}

export async function fetchEnterpriseDocuments(): Promise<{ documents: EnterpriseDocumentVersion[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/documents`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Documents request failed: ${res.status}`);
  return res.json();
}

export async function recommendEnterpriseDistill(versionId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/distill`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ version_id: versionId, reason: 'user' }),
  });
  if (!res.ok) throw new Error(`Distill request failed: ${res.status}`);
}

export async function fetchEnterpriseWikis(): Promise<{ wikis: EnterpriseWiki[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/wikis`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Wiki list request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseWiki(id: string, version?: number): Promise<EnterpriseWiki> {
  const qs = new URLSearchParams({ id });
  if (version) qs.set('version', String(version));
  const res = await fetch(`${BASE}/api/enterprise/wiki?${qs}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Wiki request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseEvidence(id: string): Promise<EnterpriseEvidence> {
  const res = await fetch(`${BASE}/api/enterprise/evidence?id=${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Evidence request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseGraph(query: string): Promise<{ relations: EnterpriseGraphRelation[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/graph?q=${encodeURIComponent(query)}&depth=2&limit=10`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Graph request failed: ${res.status}`);
  return res.json();
}

export interface EnterpriseOverview {
  documents: number;
  versions: number;
  active_versions: number;
  pending_versions: number;
  failed_versions: number;
  raw_blocks: number;
  active_evidence: number;
  active_summaries: number;
  stale_summaries: number;
  grounded_summaries: number;
  covered_summaries: number;
  active_wikis: number;
  stale_wikis: number;
  active_relations: number;
  active_graph_supports: number;
  effective_hits: number;
  recommended_sources: number;
  pending_tasks: number;
  running_tasks: number;
  failed_tasks: number;
}

export interface EnterpriseTask {
  task_id: string;
  version_id?: string;
  logical_name?: string;
  task_type: string;
  status: string;
  attempt: number;
  error_code?: string;
  error_message?: string;
  started_at?: string;
  finished_at?: string;
  created_at: string;
}

export interface EnterpriseEvidenceListItem {
  evidence_id: string;
  document_id: string;
  logical_name: string;
  version_id: string;
  version_no: number;
  original_filename: string;
  block_id: string;
  block_type: string;
  raw_location: Record<string, unknown>;
  excerpt: string;
  active: boolean;
  created_at: string;
}

export interface EnterpriseCapabilities {
  auth: boolean;
  users: boolean;
  documents: boolean;
  upload: boolean;
  tasks: boolean;
  evidence: boolean;
  search: boolean;
  package_sources: boolean;
  promotion: boolean;
  graph: boolean;
  web: boolean;
}

export interface EnterpriseSearchFilters {
  source_department?: string;
  material_category?: string;
  company?: string;
  document_type?: string;
  version_ids?: string[];
}

export interface EnterpriseSearchResult {
  result_type: 'RAW' | 'SUMMARY' | 'WIKI' | 'GRAPH' | string;
  knowledge: string;
  score: number;
  evidence: EnterpriseEvidence[];
}

export interface EnterpriseSearchResponse {
  search_id: string;
  results: EnterpriseSearchResult[];
}

export interface EnterprisePublishedPackage {
  package_id: string;
  object_key: string;
  download_url: string;
  expires_at: string;
  manifest: {
    generated_at: string;
    evidence_ids: string[];
    versions: Array<{
      version_id: string;
      document_id: string;
      original_filename: string;
      object_key: string;
      archive_path: string;
      evidence_ids: string[];
    }>;
  };
}

export async function fetchEnterpriseOverview(): Promise<EnterpriseOverview> {
  const res = await fetch(`${BASE}/api/enterprise/overview`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Overview request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseTasks(limit = 200): Promise<{ tasks: EnterpriseTask[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/tasks?limit=${limit}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Tasks request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseEvidences(limit = 200): Promise<{ evidences: EnterpriseEvidenceListItem[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/evidences?limit=${limit}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Evidence list request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseCapabilities(): Promise<EnterpriseCapabilities> {
  const res = await fetch(`${BASE}/api/enterprise/capabilities`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Capabilities request failed: ${res.status}`);
  return res.json();
}

export async function searchEnterpriseKnowledge(
  query: string,
  filters: EnterpriseSearchFilters = {},
  limit = 8,
): Promise<EnterpriseSearchResponse> {
  const res = await fetch(`${BASE}/api/enterprise/search`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, filters, limit }),
  });
  if (!res.ok) throw new Error(`Knowledge search failed: ${res.status}`);
  return res.json();
}

export async function packageEnterpriseSources(evidenceIds: string[]): Promise<EnterprisePublishedPackage> {
  const res = await fetch(`${BASE}/api/enterprise/package`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ evidence_ids: evidenceIds }),
  });
  if (!res.ok) throw new Error(`Source package request failed: ${res.status}`);
  return res.json();
}

export type EnterpriseRole = 'ADMIN' | 'USER';

export interface EnterpriseUser {
  user_id: string;
  username: string;
  display_name: string;
  role: EnterpriseRole;
  status: 'ACTIVE' | 'DISABLED';
  last_login_at?: string;
  created_at: string;
  updated_at: string;
}

export interface EnterpriseAuthStatus {
  setup_required: boolean;
  users: number;
}

export interface EnterpriseUploadResult {
  document_id: string;
  version_id: string;
  version_no: number;
  reused: boolean;
  retried: boolean;
  block_count: number;
  chunk_count: number;
  evidence_count: number;
  original_object_key: string;
  warnings?: string[];
}

async function responseError(res: Response, fallback: string): Promise<Error> {
  try {
    const payload = await res.json();
    if (payload?.error) return new Error(String(payload.error));
  } catch {
    // Fall through to the status-based message.
  }
  return new Error(`${fallback}: ${res.status}`);
}

export async function fetchEnterpriseAuthStatus(): Promise<EnterpriseAuthStatus> {
  const res = await fetch(`${BASE}/api/enterprise/auth/status`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Authentication status failed');
  return res.json();
}

export async function fetchEnterpriseMe(): Promise<EnterpriseUser> {
  const res = await fetch(`${BASE}/api/enterprise/auth/me`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Session lookup failed');
  const payload = await res.json();
  return payload.user;
}

export async function setupEnterpriseAdmin(input: { username: string; display_name: string; password: string }): Promise<EnterpriseUser> {
  const res = await fetch(`${BASE}/api/enterprise/auth/setup`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...input, role: 'ADMIN' }),
  });
  if (!res.ok) throw await responseError(res, 'Initial admin setup failed');
  const payload = await res.json();
  return payload.user;
}

export async function loginEnterprise(username: string, password: string): Promise<EnterpriseUser> {
  const res = await fetch(`${BASE}/api/enterprise/auth/login`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw await responseError(res, 'Login failed');
  const payload = await res.json();
  return payload.user;
}

export async function logoutEnterprise(): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/auth/logout`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Logout failed');
}

export async function changeEnterprisePassword(currentPassword: string, newPassword: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/auth/password`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }),
  });
  if (!res.ok) throw await responseError(res, 'Password change failed');
}

export async function fetchEnterpriseUsers(): Promise<{ users: EnterpriseUser[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/users`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'User list failed');
  return res.json();
}

export async function createEnterpriseUser(input: { username: string; display_name: string; password: string; role: EnterpriseRole }): Promise<EnterpriseUser> {
  const res = await fetch(`${BASE}/api/enterprise/users`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Create user failed');
  const payload = await res.json();
  return payload.user;
}

export async function updateEnterpriseUser(userId: string, input: { display_name?: string; role?: EnterpriseRole; status?: 'ACTIVE' | 'DISABLED' }): Promise<EnterpriseUser> {
  const res = await fetch(`${BASE}/api/enterprise/user?id=${encodeURIComponent(userId)}`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Update user failed');
  const payload = await res.json();
  return payload.user;
}

export async function resetEnterpriseUserPassword(userId: string, newPassword: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/user/password/reset`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ user_id: userId, new_password: newPassword }),
  });
  if (!res.ok) throw await responseError(res, 'Reset password failed');
}

export async function uploadEnterpriseDocument(file: File, metadata: {
  logical_name?: string;
  source_department?: string;
  material_category?: string;
  company?: string;
  document_type?: string;
  document_date?: string;
  tags?: string;
}): Promise<EnterpriseUploadResult> {
  const form = new FormData();
  form.append('file', file, file.name);
  for (const [key, value] of Object.entries(metadata)) {
    if (value?.trim()) form.append(key, value.trim());
  }
  const res = await fetch(`${BASE}/api/enterprise/upload`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  if (!res.ok) throw await responseError(res, 'Document upload failed');
  return res.json();
}
