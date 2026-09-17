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
  manual_tags?: string[];
  parsed_at?: string;
  summary_count: number;
  grounded_summaries: number;
  covered_summaries: number;
  recommendation?: string;
  recommendation_reason?: string;
  effective_hits: number;
}

export interface EnterpriseRawBlockPreview {
  block_id: string;
  block_no: number;
  block_type: string;
  heading_path: string[];
  page_start?: number;
  page_end?: number;
  evidence_id?: string;
  content: string;
  content_hash: string;
}

export interface EnterpriseDocumentKnowledgeStatus {
  raw_blocks: number;
  evidence: number;
  summaries: number;
  active_summaries: number;
  grounded_summaries: number;
  covered_summaries: number;
  source_entities: number;
  applied_entity_mappings: number;
  active_relations: number;
  relation_evidence_supports: number;
  wiki_claims: number;
  wiki_evidence: number;
  pending_tasks: number;
  running_tasks: number;
  failed_tasks: number;
}

export interface EnterpriseDocumentDetail {
  document_id: string;
  version_id: string;
  knowledge: EnterpriseDocumentKnowledgeStatus;
  blocks: EnterpriseRawBlockPreview[];
}

export interface EnterpriseEvidence {
  evidence_id: string;
  document_id: string;
  version_id: string;
  object_key: string;
  source_filename: string;
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
  source_type: string;
  target_entity_id: string;
  target_name: string;
  target_type: string;
  relation_type: string;
  valid_from?: string;
  valid_to?: string;
  conflict_status: string;
  evidence_ids: string[];
  score: number;
}

export async function fetchEnterpriseDocuments(params: EnterprisePageParams = {}): Promise<{ documents: EnterpriseDocumentVersion[]; total: number; page: number; page_size: number }> {
  const res = await fetch(`${BASE}/api/enterprise/documents?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Documents request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseDocumentDetail(versionId: string, blockLimit = 120): Promise<EnterpriseDocumentDetail> {
  const qs = new URLSearchParams({ version_id: versionId, block_limit: String(blockLimit) });
  const res = await fetch(`${BASE}/api/enterprise/document/detail?${qs}`, { headers: authHeaders() });
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
  const res = await fetch(`${BASE}/api/enterprise/document?${qs}`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Document metadata update failed');
  return res.json();
}

export async function recommendEnterpriseKnowledgeGeneration(versionId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/generation/recommend`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ version_id: versionId, reason: 'user' }),
  });
  if (!res.ok) throw new Error(`Knowledge generation recommendation failed: ${res.status}`);
}

export async function startEnterpriseKnowledgeGeneration(versionId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/generation/start`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ version_id: versionId }),
  });
  if (!res.ok) throw await responseError(res, 'Knowledge generation start failed');
}

export async function retryEnterpriseGenerationTask(taskId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/generation/task/retry`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ task_id: taskId }),
  });
  if (!res.ok) throw await responseError(res, 'Knowledge generation retry failed');
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

export async function fetchEnterpriseGraph(query: string, options: { depth?: 1 | 2; limit?: number; relation_type?: string; valid_at?: string } = {}): Promise<{ relations: EnterpriseGraphRelation[]; total: number }> {
  const qs = new URLSearchParams({ q: query, depth: String(options.depth || 2), limit: String(options.limit || 30) });
  if (options.relation_type?.trim()) qs.set('relation_type', options.relation_type.trim());
  if (options.valid_at?.trim()) qs.set('valid_at', options.valid_at.trim());
  const res = await fetch(`${BASE}/api/enterprise/graph?${qs}`, { headers: authHeaders() });
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

export interface EnterpriseQualityIssue {
  issue_type: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW' | string;
  title: string;
  detail: string;
  version_id?: string;
  artifact_id?: string;
  evidence_ids: string[];
  created_at?: string;
}

export interface EnterpriseQuality {
  summary_grounding_failures: number;
  summary_coverage_failures: number;
  wiki_grounding_failures: number;
  wiki_temporal_conflicts: number;
  relation_conflicts: number;
  unresolved_entities: number;
  failed_generation_tasks: number;
  issues: EnterpriseQualityIssue[];
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
  adaptive_query?: boolean;
  query_feedback?: boolean;
  query_history?: boolean;
  query_groups?: boolean;
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

export async function fetchEnterpriseQuality(limit = 120): Promise<EnterpriseQuality> {
  const res = await fetch(`${BASE}/api/enterprise/quality?limit=${encodeURIComponent(String(limit))}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Knowledge quality request failed');
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

export interface EnterpriseModelConfiguration {
  chat_base_url: string;
  chat_api_key_configured: boolean;
  small_model: string;
  large_model: string;
  chat_timeout_seconds: number;
  embedding_base_url: string;
  embedding_api_key_configured: boolean;
  embedding_model: string;
  embedding_dimensions: number;
  embedding_batch_size: number;
  embedding_timeout_seconds: number;
  rerank_base_url: string;
  rerank_api_key_configured: boolean;
  rerank_model: string;
  rerank_timeout_seconds: number;
  updated_at?: string;
}

export interface EnterpriseModelConfigurationUpdate {
  chat_base_url?: string;
  chat_api_key?: string;
  clear_chat_api_key?: boolean;
  small_model?: string;
  large_model?: string;
  chat_timeout_seconds?: number;
  embedding_base_url?: string;
  embedding_api_key?: string;
  clear_embedding_api_key?: boolean;
  embedding_model?: string;
  embedding_dimensions?: number;
  embedding_batch_size?: number;
  embedding_timeout_seconds?: number;
  rerank_base_url?: string;
  rerank_api_key?: string;
  clear_rerank_api_key?: boolean;
  rerank_model?: string;
  rerank_timeout_seconds?: number;
}

export interface EnterpriseModelProfile {
  profile_id: string;
  name: string;
  active: boolean;
  chat_base_url: string;
  chat_api_key_configured: boolean;
  chat_api_key_masked: string;
  small_model: string;
  large_model: string;
  chat_timeout_seconds: number;
  embedding_base_url: string;
  embedding_api_key_configured: boolean;
  embedding_api_key_masked: string;
  embedding_model: string;
  embedding_dimensions: number;
  embedding_batch_size: number;
  embedding_timeout_seconds: number;
  rerank_base_url: string;
  rerank_api_key_configured: boolean;
  rerank_api_key_masked: string;
  rerank_model: string;
  rerank_timeout_seconds: number;
  created_at?: string;
  updated_at?: string;
}

export interface EnterpriseModelProfiles {
  active_profile_id: string;
  profiles: EnterpriseModelProfile[];
}

export interface EnterpriseModelProfileUpdate {
  name: string;
  chat_base_url: string;
  chat_api_key?: string;
  small_model: string;
  large_model: string;
  chat_timeout_seconds: number;
  embedding_base_url: string;
  embedding_api_key?: string;
  embedding_model: string;
  embedding_dimensions: number;
  embedding_batch_size: number;
  embedding_timeout_seconds: number;
  rerank_base_url: string;
  rerank_api_key?: string;
  rerank_model: string;
  rerank_timeout_seconds: number;
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

export async function fetchEnterpriseModelConfiguration(): Promise<EnterpriseModelConfiguration> {
  const res = await fetch(`${BASE}/api/enterprise/model-configuration`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model configuration request failed');
  return res.json();
}

export async function updateEnterpriseModelConfiguration(input: EnterpriseModelConfigurationUpdate): Promise<EnterpriseModelConfiguration> {
  const res = await fetch(`${BASE}/api/enterprise/model-configuration`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Model configuration update failed');
  return res.json();
}

export async function fetchEnterpriseModelProfiles(): Promise<EnterpriseModelProfiles> {
  const res = await fetch(`${BASE}/api/enterprise/model-configurations`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profiles request failed');
  return res.json();
}

export async function createEnterpriseModelProfile(name: string): Promise<EnterpriseModelProfile> {
  const res = await fetch(`${BASE}/api/enterprise/model-configurations`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name }),
  });
  if (!res.ok) throw await responseError(res, 'Model profile create failed');
  return res.json();
}

export async function updateEnterpriseModelProfile(profileId: string, input: EnterpriseModelProfileUpdate): Promise<EnterpriseModelProfile> {
  const res = await fetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}`, {
    method: 'PUT', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Model profile update failed');
  return res.json();
}

export async function copyEnterpriseModelProfile(profileId: string): Promise<EnterpriseModelProfile> {
  const res = await fetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}/copy`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profile copy failed');
  return res.json();
}

export async function activateEnterpriseModelProfile(profileId: string): Promise<EnterpriseModelProfile> {
  const res = await fetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}/activate`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profile activation failed');
  return res.json();
}

export async function deleteEnterpriseModelProfile(profileId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profile delete failed');
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

export type EnterpriseQueryStatus = 'RUNNING' | 'WAITING_USER' | 'ANSWERED' | 'INSUFFICIENT' | 'FAILED';

export interface EnterpriseQueryResponse {
  session_id: string;
  status: EnterpriseQueryStatus;
  answer: string;
  evidence_only: boolean;
  clarification_question: string;
  choices: string[];
  citations: EnterpriseAskCitation[];
}

export interface EnterpriseQuerySessionSummary {
  session_id: string;
  question: string;
  status: EnterpriseQueryStatus;
  answer: string;
  created_at: string;
  updated_at: string;
}

export type EnterpriseQueryPackageStatus = 'AVAILABLE' | 'DELETED' | 'MISSING';

export interface EnterpriseQueryPackage {
  package_id: string;
  status: EnterpriseQueryPackageStatus;
  download_url?: string;
  expires_at?: string;
  created_at: string;
}

export interface EnterpriseQueryHistoryRecord {
  session_id: string;
  name: string;
  question: string;
  status: EnterpriseQueryStatus;
  answer: string;
  evidence_only: boolean;
  citation_count: number;
  group_id?: string;
  group_name?: string;
  created_at: string;
  updated_at: string;
  latest_package?: EnterpriseQueryPackage;
}

export interface EnterpriseQueryGroup {
  group_id: string;
  name: string;
  records: EnterpriseQueryHistoryRecord[];
  created_at: string;
  updated_at: string;
  latest_package?: EnterpriseQueryPackage;
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

export async function queryEnterpriseKnowledge(question: string, filters: EnterpriseSearchFilters = {}, limit = 8): Promise<EnterpriseQueryResponse> {
  const res = await fetch(`${BASE}/api/enterprise/query`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ question, filters, limit }) });
  if (!res.ok) throw await responseError(res, 'Enterprise knowledge query failed');
  return res.json();
}

export async function continueEnterpriseQuery(sessionId: string, clarification: string, limit = 8): Promise<EnterpriseQueryResponse> {
  const res = await fetch(`${BASE}/api/enterprise/query/continue`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ session_id: sessionId, clarification, limit }) });
  if (!res.ok) throw await responseError(res, 'Enterprise query continuation failed');
  return res.json();
}

export async function fetchEnterpriseQuerySession(sessionId: string): Promise<EnterpriseQueryResponse> {
  const res = await fetch(`${BASE}/api/enterprise/query/session?id=${encodeURIComponent(sessionId)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Enterprise query session failed');
  return res.json();
}

export async function fetchEnterpriseQuerySessions(limit = 20): Promise<{ sessions: EnterpriseQuerySessionSummary[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/query/sessions?limit=${encodeURIComponent(String(limit))}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Enterprise query history failed');
  return res.json();
}

export async function fetchEnterpriseQueryHistory(limit = 100): Promise<{ records: EnterpriseQueryHistoryRecord[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/query/history?limit=${encodeURIComponent(String(limit))}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query record list failed');
  return res.json();
}

export async function renameEnterpriseQueryRecord(sessionId: string, name: string): Promise<EnterpriseQueryHistoryRecord> {
  const res = await fetch(`${BASE}/api/enterprise/query/history/record?id=${encodeURIComponent(sessionId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
  if (!res.ok) throw await responseError(res, 'Query record rename failed');
  return res.json();
}

export async function moveEnterpriseQueryRecord(sessionId: string, groupId?: string): Promise<EnterpriseQueryHistoryRecord> {
  const res = await fetch(`${BASE}/api/enterprise/query/history/record/group?id=${encodeURIComponent(sessionId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ group_id: groupId || null }) });
  if (!res.ok) throw await responseError(res, 'Query record group update failed');
  return res.json();
}

export async function deleteEnterpriseQueryRecord(sessionId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/query/history/record?id=${encodeURIComponent(sessionId)}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query record delete failed');
}

export async function packageEnterpriseQueryRecord(sessionId: string, regenerate = false): Promise<EnterpriseQueryPackage> {
  const res = await fetch(`${BASE}/api/enterprise/query/history/record/package`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ session_id: sessionId, regenerate }) });
  if (!res.ok) throw await responseError(res, 'Query record package failed');
  return res.json();
}

export async function fetchEnterpriseQueryGroups(): Promise<{ groups: EnterpriseQueryGroup[]; total: number }> {
  const res = await fetch(`${BASE}/api/enterprise/query/groups`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query group list failed');
  return res.json();
}

export async function createEnterpriseQueryGroup(name: string, sessionIds: string[]): Promise<EnterpriseQueryGroup> {
  const res = await fetch(`${BASE}/api/enterprise/query/groups`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name, session_ids: sessionIds }) });
  if (!res.ok) throw await responseError(res, 'Query group creation failed');
  return res.json();
}

export async function renameEnterpriseQueryGroup(groupId: string, name: string): Promise<EnterpriseQueryGroup> {
  const res = await fetch(`${BASE}/api/enterprise/query/group?id=${encodeURIComponent(groupId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
  if (!res.ok) throw await responseError(res, 'Query group rename failed');
  return res.json();
}

export async function dissolveEnterpriseQueryGroup(groupId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/query/group/dissolve`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ group_id: groupId }) });
  if (!res.ok) throw await responseError(res, 'Query group dissolve failed');
}

export async function deleteEnterpriseQueryGroup(groupId: string): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/query/group?id=${encodeURIComponent(groupId)}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Query group delete failed');
}

export async function packageEnterpriseQueryGroup(groupId: string, regenerate = false): Promise<EnterpriseQueryPackage> {
  const res = await fetch(`${BASE}/api/enterprise/query/group/package`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ group_id: groupId, regenerate }) });
  if (!res.ok) throw await responseError(res, 'Query group package failed');
  return res.json();
}

export async function submitEnterpriseQueryFeedback(sessionId: string, rating: 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL', category = '', comment = ''): Promise<void> {
  const res = await fetch(`${BASE}/api/enterprise/query/feedback`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ session_id: sessionId, rating, category, comment }) });
  if (!res.ok) throw await responseError(res, 'Enterprise query feedback failed');
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
