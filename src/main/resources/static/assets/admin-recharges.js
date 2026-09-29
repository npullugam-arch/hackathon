import { request } from './api.js';

const $ = id => document.getElementById(id);
const form = $('recharges-filters');
const section = $('recharges-section');
const currency = value => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(Number(value) / 100);
const date = value => value ? new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : 'Not available';
const label = status => ({ SUCCESSFUL: 'Successful', FAILED: 'Failed', PENDING: 'Pending' })[status] || 'Pending';
let applied = new URLSearchParams(), currentPage = 0, totalPages = 0, loading = false, version = 0, controller;
let detailVersion = 0;

function error(message = '') { $('recharges-error').textContent = message; $('recharges-error').hidden = !message; }
function controls() {
  $('recharges-previous').disabled = loading || currentPage === 0;
  $('recharges-next').disabled = loading || currentPage + 1 >= totalPages;
  $('recharges-refresh').disabled = loading;
  section.setAttribute('aria-busy', String(loading));
}
function cell(row, value, className = '') {
  const td = document.createElement('td'); td.textContent = value ?? 'Not provided'; td.className = className; row.append(td); return td;
}
function render(data) {
  currentPage = data.page; totalPages = data.totalPages;
  for (const [id, field] of [['attempts', 'totalAttempts'], ['successful', 'successful'], ['failed', 'failed'], ['pending', 'pending']])
    $(`recharges-${id}`).textContent = data.summary[field].toLocaleString();
  $('recharges-processed').textContent = currency(data.summary.successfulAmountPaise);
  $('recharges-credited').textContent = currency(data.summary.walletCreditedPaise);
  const rows = $('recharges-rows'); rows.replaceChildren();
  for (const item of data.items) {
    const row = document.createElement('tr'); row.dataset.id = item.rechargeId;
    const reference = cell(row, '', 'reference'); const link = document.createElement('button');
    link.type = 'button'; link.className = 'transaction-link'; link.textContent = item.transactionId;
    link.setAttribute('aria-label', `View transaction ${item.transactionId}`);
    link.addEventListener('click', event => { event.stopPropagation(); showDetails(item.rechargeId); });
    reference.append(link);
    cell(row, item.userName); cell(row, item.userEmail); cell(row, item.userPhone); cell(row, item.userId, 'reference');
    cell(row, currency(item.amountPaise), 'amount-cell'); cell(row, item.razorpayOrderId, 'reference');
    cell(row, item.razorpayPaymentId || 'Not received', 'reference');
    const status = cell(row, ''); const badge = document.createElement('span');
    badge.className = `recharge-status ${item.status.toLowerCase()}`; badge.textContent = label(item.status); status.append(badge);
    cell(row, item.transactionType); cell(row, date(item.createdAt));
    row.addEventListener('click', () => showDetails(item.rechargeId)); rows.append(row);
  }
  $('recharges-table-wrap').hidden = !data.items.length; $('recharges-empty').hidden = data.items.length > 0;
  $('recharges-result-count').textContent = data.total ? `${data.page * data.size + 1}–${data.page * data.size + data.items.length} of ${data.total.toLocaleString()} recharge transactions · Newest first` : '0 recharge transactions';
  $('recharges-page').textContent = `Page ${data.page + 1} of ${Math.max(1, data.totalPages)}`;
  $('recharges-updated').textContent = `Updated ${date(data.refreshedAt)} · Refreshes every 10 seconds while visible.`;
}
async function load(page = currentPage, quiet = false) {
  const ticket = ++version; controller?.abort(); controller = new AbortController();
  const params = new URLSearchParams(applied); params.set('page', page); params.set('size', $('recharges-size').value);
  loading = true; controls(); if (!quiet) $('recharges-loading').hidden = false;
  try {
    const data = await request(`/api/admin/recharges?${params}`, { signal: controller.signal });
    if (ticket !== version) return;
    render(data); error();
  } catch (ex) {
    if (ticket !== version) return;
    if (ex.status === 401 || ex.status === 403) return location.replace('/admin');
    error(ex.message); $('recharges-updated').textContent = 'Unable to refresh. Any displayed results are from the last successful update.';
  } finally { if (ticket === version) { loading = false; $('recharges-loading').hidden = true; controls(); } }
}
function applyFilters() {
  if (!form.reportValidity()) return;
  const fields = new FormData(form); const from = fields.get('from'), to = fields.get('to');
  if (from && to && from > to) return error('The end date must be on or after the start date.');
  const params = new URLSearchParams();
  for (const [key, value] of fields) {
    if (!value.trim()) continue;
    if (key === 'from' || key === 'to') {
      const d = new Date(`${value}T00:00:00`); if (key === 'to') d.setDate(d.getDate() + 1);
      params.set(key, d.toISOString());
    } else params.set(key, value.trim());
  }
  applied = params; load(0);
}
form.addEventListener('submit', event => { event.preventDefault(); applyFilters(); });
$('recharges-reset').addEventListener('click', () => { form.reset(); applied = new URLSearchParams(); load(0); });
$('recharges-size').addEventListener('change', () => load(0));
$('recharges-refresh').addEventListener('click', () => load());
$('recharges-previous').addEventListener('click', () => { if (!loading && currentPage > 0) load(currentPage - 1); });
$('recharges-next').addEventListener('click', () => { if (!loading && currentPage + 1 < totalPages) load(currentPage + 1); });
async function showDetails(id) {
  const ticket = ++detailVersion; const dialog = $('recharge-details'), message = $('recharge-details-message');
  $('recharge-details-fields').replaceChildren(); message.hidden = false; message.textContent = 'Loading transaction details...';
  message.className = 'message info'; if (!dialog.open) dialog.showModal();
  try {
    const item = await request(`/api/admin/recharges/${encodeURIComponent(id)}`);
    if (ticket !== detailVersion || !dialog.open) return;
    const fields = [['Transaction ID', item.transactionId], ['Recharge reference', item.rechargeId],
      ['Wallet credit transaction ID', item.walletTransactionId || 'No wallet credit'], ['Status', label(item.status)],
      ['User name', item.userName], ['Email', item.userEmail], ['Phone number', item.userPhone], ['User ID', item.userId],
      ['Recharge amount', currency(item.amountPaise)], ['Wallet credited', currency(item.walletCreditedPaise)],
      ['Razorpay Order ID', item.razorpayOrderId], ['Razorpay Payment ID', item.razorpayPaymentId || 'Not received'],
      ['Provider status', item.providerStatus || 'Not confirmed'], ['Transaction type', item.transactionType],
      ['Created', date(item.createdAt)], ['Credited', date(item.creditedAt)], ['Provider status checked', date(item.statusCheckedAt)]];
    for (const [title, value] of fields) {
      const group = document.createElement('div'), dt = document.createElement('dt'), dd = document.createElement('dd');
      dt.textContent = title; dd.textContent = value ?? 'Not provided'; group.append(dt, dd); $('recharge-details-fields').append(group);
    }
    message.className = `message ${item.status === 'SUCCESSFUL' ? 'success' : 'info'}`;
    message.textContent = item.status === 'SUCCESSFUL' ? 'Backend verification completed. This payment was credited once.'
      : item.status === 'FAILED' ? 'Razorpay confirmed a failed payment. No wallet credit was created.'
      : 'This recharge has no verified wallet credit. It may be incomplete, cancelled, or awaiting verification.';
  } catch (ex) {
    if (ticket !== detailVersion) return;
    if (ex.status === 401 || ex.status === 403) return location.replace('/admin');
    message.className = 'message'; message.textContent = ex.message;
  }
}
$('recharge-details-close').addEventListener('click', () => $('recharge-details').close());
$('recharge-details').addEventListener('close', () => { detailVersion++; });
document.addEventListener('admin-section-change', event => { if (event.detail === 'recharges-section') load(); });
document.addEventListener('visibilitychange', () => { if (!document.hidden && !section.hidden && !loading) load(currentPage, true); });
setInterval(() => { if (!document.hidden && !section.hidden && !loading && !$('recharge-details').open) load(currentPage, true); }, 10000);
