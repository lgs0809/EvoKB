const BASE = '';
const authHeaders = (): Record<string, string> => ({});

export interface EnterprisePageResponse<T> {
  total: number;
  page: number;
  page_size: number;
  items?: T[];
}

export interface EnterprisePageParams {
  page?: number;
  page_size?: number;
  q?: string;
  lifecycle?: string;
  status?: string;
  source?: string;
  active?: boolean;
  company?: string;
  source_department?: string;
  document_type?: string;
  material_category?: string;
  document_id?: string;
  wiki_type?: string;
  wiki_id?: string;
  canonical_subject_id?: string;
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

export async function fetchEnterpriseDocuments(params: EnterprisePageParams = {}): Promise<{ documents: EnterpriseDocumentVersion[]; total: number; page: number; page_size: number }> {
  const res = await fetch(`${BASE}/api/enterprise/documents?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Documents request failed: ${res.status}`);
  return res.json();
}

export async function recommendEnterpriseKnowledgeGeneration(versionId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/generation/recommend`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ version_id: versionId, reason: 'user' }),
  });
  if (!res.ok) throw new Error(`Knowledge generation recommendation failed: ${res.status}`);
}

export async function fetchEnterpriseWikis(params: EnterprisePageParams = {}): Promise<{ wikis: EnterpriseWiki[]; total: number; page: number; page_size: number }> {
  const res = await fetch(`${BASE}/api/enterprise/wikis?${pageQuery(params)}`, { headers: authHeaders() });
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
  source?: 'COMPILE' | 'INGEST' | string;
  batch_id?: string;
  file_id?: string;
  version_id?: string;
  logical_name?: string;
  task_type: string;
  status: string;
  progress_stage?: string;
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
  batch_ingestion: boolean;
  tasks: boolean;
  audit: boolean;
  evidence: boolean;
  source_viewer: boolean;
  ask: boolean;
  ask_answering: boolean;
  search: boolean;
  package_sources: boolean;
  generation_recommendation: boolean;
  promotion?: boolean;
  artifact_evaluation?: boolean;
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

export async function fetchEnterpriseTasks(params: EnterprisePageParams = {}): Promise<{ tasks: EnterpriseTask[]; total: number; page: number; page_size: number }> {
  const res = await fetch(`${BASE}/api/enterprise/tasks?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Tasks request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseEvidences(params: EnterprisePageParams = {}): Promise<{ evidences: EnterpriseEvidenceListItem[]; total: number; page: number; page_size: number }> {
  const res = await fetch(`${BASE}/api/enterprise/evidences?${pageQuery(params)}`, { headers: authHeaders() });
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

export interface EnterpriseAccessPolicy {
  user_upload_enabled: boolean;
  can_upload: boolean;
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

export async function fetchEnterpriseAccess(): Promise<EnterpriseAccessPolicy> {
  const res = await fetch(`${BASE}/api/enterprise/access`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Access policy request failed');
  return res.json();
}

export async function updateEnterpriseAccess(userUploadEnabled: boolean): Promise<EnterpriseAccessPolicy> {
  const res = await fetch(`${BASE}/api/enterprise/access`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ user_upload_enabled: userUploadEnabled }),
  });
  if (!res.ok) throw await responseError(res, 'Access policy update failed');
  return res.json();
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

export interface EnterpriseFacets {
  companies: string[];
  source_departments: string[];
  document_types: string[];
  material_categories: string[];
}

export interface EnterpriseIngestionFile {
  file_id: string;
  batch_id: string;
  relative_path: string;
  original_filename: string;
  size_bytes: number;
  sha256: string;
  logical_name: string;
  source_department?: string;
  material_category?: string;
  company?: string;
  document_type?: string;
  document_date?: string;
  manual_tags?: string[];
  status: string;
  progress_stage: string;
  attempt: number;
  document_id?: string;
  version_id?: string;
  expected_behavior?: 'CREATE_DOCUMENT' | 'CREATE_VERSION' | 'REUSE_VERSION' | 'DUPLICATE_CONTENT' | string;
  existing_document_id?: string;
  existing_version_no?: number;
  duplicate_document_id?: string;
  duplicate_version_id?: string;
  duplicate_logical_name?: string;
  duplicate_version_lifecycle?: string;
  error_code?: string;
  error_message?: string;
  created_at: string;
  updated_at: string;
  started_at?: string;
  finished_at?: string;
}

export interface EnterpriseIngestionBatch {
  batch_id: string;
  created_by?: string;
  created_by_username: string;
  status: string;
  source_count: number;
  file_count: number;
  staged_count: number;
  queued_count: number;
  running_count: number;
  succeeded_count: number;
  failed_count: number;
  created_at: string;
  updated_at: string;
  started_at?: string;
  finished_at?: string;
  files?: EnterpriseIngestionFile[];
}

export interface EnterpriseAuditEvent {
  event_id: string;
  actor_user_id?: string;
  actor_username: string;
  actor_role: string;
  action: string;
  target_type?: string;
  target_id?: string;
  target_name?: string;
  outcome: 'SUCCESS' | 'FAILURE';
  request_id?: string;
  ip_address?: string;
  user_agent?: string;
  details?: Record<string, unknown>;
  created_at: string;
}

export interface EnterpriseEvidenceSource {
  evidence_id: string;
  document_id: string;
  version_id: string;
  object_key: string;
  content_type?: string;
  size_bytes: number;
  location: Record<string, unknown>;
  view_url: string;
  download_url: string;
  expires_at: string;
}

export interface EnterpriseAskCitation {
  index: number;
  result_type: string;
  evidence: EnterpriseEvidence;
}

export interface EnterpriseAskResponse {
  search_id: string;
  answer: string;
  citations: EnterpriseAskCitation[];
  evidence_only: boolean;
}

export async function fetchEnterpriseFacets(): Promise<EnterpriseFacets> {
  const res = await fetch(`${BASE}/api/enterprise/facets`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Metadata facets failed');
  return res.json();
}

export async function createEnterpriseBatch(files: Array<{ file: File; relativePath?: string }>, metadata: Record<string, string> = {}): Promise<{ batch: EnterpriseIngestionBatch; rejected: string[] }> {
  const form = new FormData();
  for (const item of files) {
    form.append('files', item.file, item.file.name);
    form.append('relative_paths', item.relativePath || item.file.name);
  }
  for (const [key, value] of Object.entries(metadata)) if (value?.trim()) form.append(key, value.trim());
  const res = await fetch(`${BASE}/api/enterprise/batches`, { method: 'POST', headers: authHeaders(), body: form });
  if (!res.ok) throw await responseError(res, 'Batch upload failed');
  return res.json();
}

export async function fetchEnterpriseBatches(params: EnterprisePageParams = {}): Promise<{ batches: EnterpriseIngestionBatch[]; total: number; page: number; page_size: number }> {
  const res = await fetch(`${BASE}/api/enterprise/batches?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Batch list failed');
  return res.json();
}

export async function fetchEnterpriseBatch(batchId: string): Promise<EnterpriseIngestionBatch> {
  const res = await fetch(`${BASE}/api/enterprise/batch?id=${encodeURIComponent(batchId)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Batch lookup failed');
  return res.json();
}

export async function updateEnterpriseBatchFile(fileId: string, input: { logical_name: string; source_department: string; material_category: string; company: string; document_type: string; document_date: string; manual_tags: string[] }): Promise<EnterpriseIngestionFile> {
  const res = await fetch(`${BASE}/api/enterprise/batch/file?id=${encodeURIComponent(fileId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
  if (!res.ok) throw await responseError(res, 'Batch file update failed');
  const payload = await res.json();
  return payload.file;
}

export async function startEnterpriseBatch(batchId: string): Promise<EnterpriseIngestionBatch> {
  const res = await fetch(`${BASE}/api/enterprise/batch/start`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ batch_id: batchId }) });
  if (!res.ok) throw await responseError(res, 'Batch start failed');
  const payload = await res.json();
  return payload.batch;
}

export async function retryEnterpriseBatchFile(fileId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/batch/file/retry`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ file_id: fileId }) });
  if (!res.ok) throw await responseError(res, 'Ingestion retry failed');
}

export async function fetchEnterpriseAudit(params: EnterprisePageParams & { actor?: string; action?: string; outcome?: string } = {}): Promise<{ events: EnterpriseAuditEvent[]; total: number; page: number; page_size: number }> {
  const res = await fetch(`${BASE}/api/enterprise/audit?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Audit request failed');
  return res.json();
}

export async function fetchEnterpriseEvidenceSource(evidenceId: string): Promise<EnterpriseEvidenceSource> {
  const res = await fetch(`${BASE}/api/enterprise/evidence/source?id=${encodeURIComponent(evidenceId)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Evidence source request failed');
  return res.json();
}

export async function askEnterpriseKnowledge(question: string, filters: EnterpriseSearchFilters = {}, limit = 8): Promise<EnterpriseAskResponse> {
  const res = await fetch(`${BASE}/api/enterprise/ask`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ question, filters, limit }) });
  if (!res.ok) throw await responseError(res, 'Enterprise Ask failed');
  return res.json();
}

function pageQuery(params: object): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '' || value === 'ALL') continue;
    query.set(key, String(value));
  }
  if (!query.has('page')) query.set('page', '1');
  if (!query.has('page_size')) query.set('page_size', '30');
  return query.toString();
}
