import { request } from './api.js';
export async function adminWrite(path, body, method = 'POST', form = false) {
  const csrf = await request('/api/admin/csrf');
  return request(path, {method, headers: {'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json', [csrf.headerName]: csrf.token},
    body: form ? new URLSearchParams(body).toString() : JSON.stringify(body ?? {})});
}
