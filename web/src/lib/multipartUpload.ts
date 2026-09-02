export interface UploadMetadata {
  logical_name: string; source_department: string; material_category: string;
  company: string; document_type: string; document_date: string | null; manual_tags: string[];
  security_level?: number | null;
}
export interface UploadSession {
  session_id: string; filename: string; size_bytes: number; part_size: number;
  part_count: number; client_sha256: string; status: string; revision: number;
  integrity_status: string; actual_sha256: string; relative_path: string; expires_at: string; batch_id: string; file_id: string; metadata: UploadMetadata;
}
export interface UploadPart { number: number; size_bytes: number; etag: string }
export interface UploadPolicy { max_file_bytes: number; part_size: number; parallel_parts: number; url_seconds: number; session_seconds: number }
export interface UploadProgress { stage: '校验文件' | '上传中' | '合并原件' | '已进入入库队列'; bytes: number; total: number; sessionId?: string }
const API = '/api/enterprise/upload-sessions';
async function request<T>(path: string, input?: unknown): Promise<T> {
  const response = await fetch(API + path, { credentials: 'same-origin', ...(input === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) }) });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || `上传请求失败 (${response.status})`);
  return body as T;
}
export function uploadPolicy(): Promise<UploadPolicy> { return request('/policy'); }
export async function listUploadSessions(): Promise<UploadSession[]> { return (await request<{ sessions: UploadSession[] }>('')).sessions; }
export function cancelUpload(session: UploadSession): Promise<UploadSession> { return request(`/${session.session_id}/cancel`, { revision: session.revision }); }
export function uploadSessionView(id: string): Promise<{ session: UploadSession; uploaded_parts: UploadPart[] }> { return request('/' + id); }
interface HashResult { sha256: string; parts: Array<{ number: number; size_bytes: number; content_md5: string }> }
function hashOriginal(file: File, partSize: number, progress: (value: UploadProgress) => void, signal?: AbortSignal): Promise<HashResult> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./originalHash.worker.ts', import.meta.url), { type: 'module' });
    const stop = () => { worker.terminate(); signal?.removeEventListener('abort', abort); };
    const abort = () => { stop(); reject(new DOMException('上传已暂停', 'AbortError')); };
    if (signal?.aborted) { abort(); return; }
    signal?.addEventListener('abort', abort, { once: true });
    worker.onerror = () => { stop(); reject(new Error('文件摘要计算失败，请重新选择原件')); };
    worker.onmessage = event => {
      const value = event.data;
      if (value.type === 'progress') progress({ stage: '校验文件', bytes: value.bytes, total: value.total });
      else if (value.type === 'complete') { stop(); resolve(value); }
      else if (value.type === 'error') { stop(); reject(new Error(value.message)); }
    };
    worker.postMessage({ file, partSize });
  });
}
function sameMetadata(one: UploadMetadata, two: UploadMetadata): boolean {
  return one.logical_name === two.logical_name && one.source_department === two.source_department
    && one.material_category === two.material_category && one.company === two.company
    && one.document_type === two.document_type && (one.document_date || null) === (two.document_date || null)
    && JSON.stringify(one.manual_tags) === JSON.stringify(two.manual_tags)
    && (one.security_level ?? null) === (two.security_level ?? null);
}
/** Four parallel byte parts, authoritative MinIO resume, then the existing durable Raw queue. */
export async function uploadOriginal(file: File, relativePath: string, metadata: UploadMetadata,
  progress: (value: UploadProgress) => void, signal?: AbortSignal): Promise<UploadSession> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  try { return await performOriginal(file, relativePath, metadata, progress, controller); }
  finally { signal?.removeEventListener('abort', abort); }
}
async function performOriginal(file: File, relativePath: string, metadata: UploadMetadata,
  progress: (value: UploadProgress) => void, controller: AbortController): Promise<UploadSession> {
  const signal = controller.signal;
  const policy = await uploadPolicy();
  if (file.size < 1 || file.size > policy.max_file_bytes) throw new Error(`单个原件不能超过 ${Math.floor(policy.max_file_bytes / 1024 / 1024)} MiB`);
  const hashed = await hashOriginal(file, policy.part_size, progress, signal);
  const existing = (await listUploadSessions()).find(session => session.status === 'UPLOADING'
    && new Date(session.expires_at).getTime() > Date.now() && session.filename === file.name
    && session.size_bytes === file.size && session.client_sha256 === hashed.sha256 && sameMetadata(session.metadata, metadata));
  const session = existing || await request<UploadSession>('', { filename: file.name, relative_path: relativePath, size_bytes: file.size, client_sha256: hashed.sha256, metadata });
  const observed = await uploadSessionView(session.session_id);
  const uploaded = new Map(observed.uploaded_parts.map(part => [part.number, part]));
  // ETags are protocol identities, not a whole-file digest. Resume also checks original Worker SHA.
  for (const part of hashed.parts) {
    const stored = uploaded.get(part.number);
    if (stored && (stored.size_bytes !== part.size_bytes || stored.etag.toLowerCase() !== hexFromMd5(part.content_md5)))
      throw new Error('存储中的分片与重选原件不一致，请取消该会话后重新上传');
  }
  let bytes = [...uploaded.values()].reduce((sum, part) => sum + part.size_bytes, 0);
  progress({ stage: '上传中', bytes, total: file.size, sessionId: session.session_id });
  const missing = hashed.parts.filter(part => !uploaded.has(part.number));
  let cursor = 0;
  const consumers = Array.from({ length: Math.min(policy.parallel_parts, missing.length) }, async () => {
    while (cursor < missing.length) {
      if (signal?.aborted) throw new DOMException('上传已暂停', 'AbortError');
      const part = missing[cursor++];
      let succeeded = false;
      for (let retry = 0; retry < 3; retry++) {
        const signed = await request<{ url: string; headers: Record<string, string> }>(`/${session.session_id}/part-url`, { number: part.number, content_md5: part.content_md5, client_sha256: hashed.sha256 });
        try {
          const response = await fetch(signed.url, { method: 'PUT', headers: signed.headers,
            body: file.slice((part.number - 1) * policy.part_size, (part.number - 1) * policy.part_size + part.size_bytes), signal });
          if (response.ok) { succeeded = true; break; }
          if (![403, 408, 429, 500, 502, 503, 504].includes(response.status)) throw new Error(`分片 ${part.number} 上传失败 (${response.status})`);
        } catch (error) {
          if (signal?.aborted || !(error instanceof TypeError)) throw error;
          // A network failure can lose only the response. Resume later reads storage facts.
        }
        await new Promise(resolve => window.setTimeout(resolve, 250 * 2 ** retry));
        // A stale URL is reauthenticated and resigned; it does not expire the entire session.
      }
      if (!succeeded) throw new Error(`分片 ${part.number} 重试后仍未完成，可重选同一原件继续上传`);
      bytes += part.size_bytes; progress({ stage: '上传中', bytes, total: file.size, sessionId: session.session_id });
    }
  });
  try { await Promise.all(consumers); }
  catch (error) {
    // A failed part stops peer requests and waits for their settlement before returning control.
    controller.abort();
    await Promise.allSettled(consumers);
    throw error;
  }
  progress({ stage: '合并原件', bytes: file.size, total: file.size, sessionId: session.session_id });
  // If a PUT response was lost, storage remains the only source of completed-part facts.
  const before = await uploadSessionView(session.session_id);
  let completed = await request<UploadSession>(`/${session.session_id}/complete`, { revision: before.session.revision, parts: before.uploaded_parts });
  for (let poll = 0; completed.status === 'COMPLETING' && poll < 120; poll++) {
    if (signal?.aborted) throw new DOMException('上传已暂停', 'AbortError');
    await new Promise(resolve => window.setTimeout(resolve, 1000));
    completed = (await uploadSessionView(session.session_id)).session;
  }
  if (completed.status !== 'COMPLETED') throw new Error('原件合并仍在对账，可稍后在上传会话中查看');
  progress({ stage: '已进入入库队列', bytes: file.size, total: file.size, sessionId: session.session_id });
  return completed;
}
function hexFromMd5(value: string) { return [...atob(value)].map(c => c.charCodeAt(0).toString(16).padStart(2, '0')).join(''); }
