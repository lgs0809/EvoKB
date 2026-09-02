import type { EnterprisePageParams, EnterpriseUploadResult, EnterpriseFacets, EnterpriseIngestionFile, EnterpriseIngestionBatch } from './contracts';
import { BASE, authHeaders, enterpriseFetch, responseError, pageQuery } from './transport';

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
  const res = await enterpriseFetch(`${BASE}/api/enterprise/upload`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  if (!res.ok) throw await responseError(res, 'Document upload failed');
  return res.json();
}

export async function fetchEnterpriseFacets(): Promise<EnterpriseFacets> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/facets`, { headers: authHeaders() });
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
  const res = await enterpriseFetch(`${BASE}/api/enterprise/batches`, { method: 'POST', headers: authHeaders(), body: form });
  if (!res.ok) throw await responseError(res, 'Batch upload failed');
  return res.json();
}

export async function fetchEnterpriseBatches(params: EnterprisePageParams = {}): Promise<{ batches: EnterpriseIngestionBatch[]; total: number; page: number; page_size: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/batches?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Batch list failed');
  return res.json();
}

export async function fetchEnterpriseBatch(batchId: string): Promise<EnterpriseIngestionBatch> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/batch?id=${encodeURIComponent(batchId)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Batch lookup failed');
  return res.json();
}

export async function updateEnterpriseBatchFile(fileId: string, input: { logical_name: string; source_department: string; material_category: string; company: string; document_type: string; document_date: string; manual_tags: string[]; security_level?: number | null; clear_security_level?: boolean }): Promise<EnterpriseIngestionFile> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/batch/file?id=${encodeURIComponent(fileId)}`, { method: 'PATCH', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
  if (!res.ok) throw await responseError(res, 'Batch file update failed');
  const payload = await res.json();
  return payload.file;
}

export async function startEnterpriseBatch(batchId: string): Promise<EnterpriseIngestionBatch> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/batch/start`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ batch_id: batchId }) });
  if (!res.ok) throw await responseError(res, 'Batch start failed');
  const payload = await res.json();
  return payload.batch;
}

export async function retryEnterpriseBatchFile(fileId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/batch/file/retry`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ file_id: fileId }) });
  if (!res.ok) throw await responseError(res, 'Ingestion retry failed');
}
