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
  security_level?: number | null;
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

export interface EnterpriseKnowledgeEntry {
  entry_id: string;
  revision: number;
  entry_type: string;
  label: string;
  aliases: string[];
  description: string;
  stage: 'CONCEPT' | 'WIKI';
  lifecycle: string;
  scope: { identity_status?: string; document_id?: string; anchors?: Array<{ type: string; value: string }> };
  updated_at: string;
  source_count: number;
  sources: Array<{
    document_id: string; version_id: string; version_no: number; logical_name: string; summary_id: string; concept_id: string;
    facts: Array<{ fact_id: string; fact_text: string; evidence_ids: string[] }>;
  }>;
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
  next_attempt_at?: string;
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

export type EnterpriseRole = 'ADMIN' | 'USER';

export interface EnterpriseUser {
  user_id: string;
  username: string;
  display_name: string;
  role: EnterpriseRole;
  status: 'ACTIVE' | 'DISABLED';
  security_clearance: number;
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
  security_level?: number | null;
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
  source_filename?: string;
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
  question?: string;
  filters?: EnterpriseSearchFilters;
  evidence_changed?: boolean;
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
  evidence_changed: boolean;
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
