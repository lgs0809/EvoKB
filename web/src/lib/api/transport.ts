export const BASE = '';

export const authHeaders = (): Record<string, string> => ({});

export const ENTERPRISE_SESSION_EXPIRED = 'enterprise-session-expired';

export async function enterpriseFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const response = await window.fetch(input, init);
  const path = new URL(input instanceof Request ? input.url : String(input), window.location.origin).pathname;
  if (response.status === 401 && path.startsWith('/api/enterprise/') && !path.startsWith('/api/enterprise/auth/')) {
    window.dispatchEvent(new Event(ENTERPRISE_SESSION_EXPIRED));
  }
  return response;
}

export async function responseError(res: Response, fallback: string): Promise<Error> {
  try {
    const payload = await res.json();
    if (payload?.error) return new Error(String(payload.error));
  } catch {
    // Fall through to the status-based message.
  }
  return new Error(`${fallback}: ${res.status}`);
}

export function pageQuery(params: object): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '' || value === 'ALL') continue;
    query.set(key, String(value));
  }
  if (!query.has('page')) query.set('page', '1');
  if (!query.has('page_size')) query.set('page_size', '30');
  return query.toString();
}
