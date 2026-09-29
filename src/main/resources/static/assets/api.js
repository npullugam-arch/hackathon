export async function request(path, options = {}) {
  let response;
  try { response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options }); }
  catch { throw new Error('Unable to connect. Check your connection and try again.'); }
  if (!response.ok) {
    const fallback = response.status === 503 ? 'Account services are temporarily unavailable. Please try again later.'
      : response.status === 403 ? 'Your security token expired. Refresh the page and try again.'
      : response.status === 401 ? 'We could not verify your sign-in. Please sign in again.'
      : 'Something went wrong. Please try again.';
    const body = await response.json().catch(() => null);
    // Display the backend's deliberately safe explanation, not a guessed expiry reason.
    const message = typeof body?.message === 'string' && body.message.length <= 500 ? body.message : fallback;
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

export async function post(path, body) {
  const csrf = await request('/api/auth/csrf');
  return request(path, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(body ?? {}) });
}

export function showMessage(text, kind = 'error') {
  const message = document.getElementById('message');
  message.textContent = text;
  message.className = `message ${kind}`;
  message.hidden = !text;
}
