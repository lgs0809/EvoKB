import type { EnterpriseUser, EnterpriseAuthStatus } from './contracts';
import { BASE, authHeaders, enterpriseFetch, responseError } from './transport';

export async function fetchEnterpriseAuthStatus(): Promise<EnterpriseAuthStatus> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/auth/status`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Authentication status failed');
  return res.json();
}

export async function fetchEnterpriseMe(): Promise<EnterpriseUser> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/auth/me`, { headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Session lookup failed');
  const payload = await res.json();
  return payload.user;
}

export async function setupEnterpriseAdmin(input: { username: string; display_name: string; password: string }): Promise<EnterpriseUser> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/auth/setup`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...input, role: 'ADMIN' }),
  });
  if (!res.ok) throw await responseError(res, 'Initial admin setup failed');
  const payload = await res.json();
  return payload.user;
}

export async function loginEnterprise(username: string, password: string): Promise<EnterpriseUser> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/auth/login`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw await responseError(res, 'Login failed');
  const payload = await res.json();
  return payload.user;
}

export async function logoutEnterprise(): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/auth/logout`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw await responseError(res, 'Logout failed');
}

export async function changeEnterprisePassword(currentPassword: string, newPassword: string): Promise<void> {
  const res = await enterpriseFetch(`${BASE}/api/enterprise/auth/password`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }),
  });
  if (!res.ok) throw await responseError(res, 'Password change failed');
}
