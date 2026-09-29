import { showMessage } from './api.js';
import { adminWrite } from './admin-api.js';
const form = document.getElementById('admin-login-form');
let busy = false;
form.addEventListener('submit', async event => {
  event.preventDefault(); if (busy) return; busy = true;
  const button = document.getElementById('sign-in'); button.disabled = true; button.textContent = 'Signing in…'; showMessage('');
  try {
    await adminWrite('/api/admin/login', {email: form.email.value.trim(), password: form.password.value}, 'POST', true);
    location.replace('/admin/dashboard');
  } catch (error) { showMessage(error.message); busy = false; button.disabled = false; button.textContent = 'Sign in to admin →'; }
});
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
