import type { EnterprisePageParams, EnterpriseOverview, EnterpriseQuality, EnterpriseTask, EnterpriseCapabilities, EnterpriseRole, EnterpriseUser, EnterpriseAccessPolicy, EnterpriseModelConfiguration, EnterpriseModelConfigurationUpdate, EnterpriseModelProfile, EnterpriseModelProfiles, EnterpriseModelProfileUpdate, EnterpriseAuditEvent } from './contracts';
import { BASE, authHeaders, enterpriseFetch, responseError, pageQuery } from './transport';

export async function recommendEnterpriseKnowledgeGeneration(versionId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/generation/recommend`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ version_id: versionId, reason: 'user' }),
  });
  if (!res.ok) throw new Error(`Knowledge generation recommendation failed: ${res.status}`);
}

export async function startEnterpriseKnowledgeGeneration(versionId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/generation/start`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ version_id: versionId }),
  });
  if (!res.ok) throw await responseError(res, 'Knowledge generation start failed');
}

export async function retryEnterpriseGenerationTask(taskId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/generation/task/retry`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ task_id: taskId }),
  });
  if (!res.ok) throw await responseError(res, 'Knowledge generation retry failed');
}

export async function fetchEnterpriseOverview(): Promise<EnterpriseOverview> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/overview`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Overview request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseQuality(limit = 120): Promise<EnterpriseQuality> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/quality?limit=${encodeURIComponent(String(limit))}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Knowledge quality request failed');
  return res.json();
}

export async function fetchEnterpriseTasks(params: EnterprisePageParams = {}): Promise<{ tasks: EnterpriseTask[]; total: number; page: number; page_size: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/tasks?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Tasks request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseCapabilities(): Promise<EnterpriseCapabilities> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/capabilities`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Capabilities request failed: ${res.status}`);
  return res.json();
}

export async function fetchEnterpriseAccess(): Promise<EnterpriseAccessPolicy> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/access`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Access policy request failed');
  return res.json();
}

export async function updateEnterpriseAccess(userUploadEnabled: boolean): Promise<EnterpriseAccessPolicy> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/access`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ user_upload_enabled: userUploadEnabled }),
  });
  if (!res.ok) throw await responseError(res, 'Access policy update failed');
  return res.json();
}

export async function fetchEnterpriseModelConfiguration(): Promise<EnterpriseModelConfiguration> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configuration`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model configuration request failed');
  return res.json();
}

export async function updateEnterpriseModelConfiguration(input: EnterpriseModelConfigurationUpdate): Promise<EnterpriseModelConfiguration> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configuration`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Model configuration update failed');
  return res.json();
}

export async function fetchEnterpriseModelProfiles(): Promise<EnterpriseModelProfiles> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configurations`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profiles request failed');
  return res.json();
}

export async function createEnterpriseModelProfile(name: string): Promise<EnterpriseModelProfile> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configurations`, {
    method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify({ name }),
  });
  if (!res.ok) throw await responseError(res, 'Model profile create failed');
  return res.json();
}

export async function updateEnterpriseModelProfile(profileId: string, input: EnterpriseModelProfileUpdate): Promise<EnterpriseModelProfile> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}`, {
    method: 'PUT', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Model profile update failed');
  return res.json();
}

export async function copyEnterpriseModelProfile(profileId: string): Promise<EnterpriseModelProfile> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}/copy`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profile copy failed');
  return res.json();
}

export async function activateEnterpriseModelProfile(profileId: string): Promise<EnterpriseModelProfile> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}/activate`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profile activation failed');
  return res.json();
}

export async function deleteEnterpriseModelProfile(profileId: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/model-configurations/${encodeURIComponent(profileId)}`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Model profile delete failed');
}

export async function fetchEnterpriseUsers(): Promise<{ users: EnterpriseUser[]; total: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/users`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'User list failed');
  return res.json();
}

export async function createEnterpriseUser(input: { username: string; display_name: string; password: string; role: EnterpriseRole }): Promise<EnterpriseUser> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/users`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Create user failed');
  const payload = await res.json();
  return payload.user;
}

export async function updateEnterpriseUser(userId: string, input: { display_name?: string; role?: EnterpriseRole; status?: 'ACTIVE' | 'DISABLED'; security_clearance?: number }): Promise<EnterpriseUser> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/user?id=${encodeURIComponent(userId)}`, {
    method: 'PATCH',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) throw await responseError(res, 'Update user failed');
  const payload = await res.json();
  return payload.user;
}

export async function resetEnterpriseUserPassword(userId: string, newPassword: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/user/password/reset`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ user_id: userId, new_password: newPassword }),
  });
  if (!res.ok) throw await responseError(res, 'Reset password failed');
}

export async function fetchEnterpriseAudit(params: EnterprisePageParams & { actor?: string; action?: string; outcome?: string } = {}): Promise<{ events: EnterpriseAuditEvent[]; total: number; page: number; page_size: number }> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/audit?${pageQuery(params)}`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Audit request failed');
  return res.json();
}
