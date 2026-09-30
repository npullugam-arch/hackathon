import { request, post, showMessage } from './api.js';

const byId = id => document.getElementById(id);
const money = paise => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(paise / 100);
let user, wallet, pending, busy = false, storageKey;
let checkoutLoader;

function controls() {
  byId('recharge-fields').disabled = busy || Boolean(pending) || !wallet?.rechargeEnabled;
  byId('retry-verification').hidden = !pending;
  byId('retry-verification').disabled = busy;
  byId('pay-now').textContent = busy ? 'Please wait...' : 'Add money →';
}
function remember(payment) {
  pending = payment;
  // Scope recovery to this signed-in account. Server still checks ownership on every request.
  try { if (payment) sessionStorage.setItem(storageKey, JSON.stringify(payment)); else sessionStorage.removeItem(storageKey); }
  catch { /* Verification still works when browser storage is unavailable. */ }
}
async function refresh() {
  wallet = await request('/api/wallet');
  byId('wallet-balance').textContent = money(wallet.balancePaise);
  byId('recharge-amount').max = wallet.maxAmount;
  byId('amount-help').textContent = wallet.rechargeEnabled
    ? `Choose ₹100 to ${money(Number(wallet.maxAmount) * 100)}, with up to two decimal places.`
    : 'Recharges are not configured yet. Please try again later.';
  const rows = byId('history-rows');
  rows.replaceChildren();
  for (const transaction of wallet.transactions) {
    const row = document.createElement('tr');
    const values = [new Date(transaction.createdAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }),
      transaction.razorpayPaymentId, `+${money(transaction.amountPaise)}`, 'Credited'];
    values.forEach((value, index) => {
      const cell = document.createElement('td');
      cell.textContent = value;
      if (index === 3) cell.className = 'credit-status';
      row.append(cell);
    });
    rows.append(row);
  }
  byId('history-empty').textContent = 'Your completed recharges will appear here.';
  byId('history-empty').hidden = wallet.transactions.length > 0;
  byId('recharge-history').hidden = !wallet.transactions.length;
  controls();
}
function loadCheckout() {
  if (window.Razorpay) return Promise.resolve();
  if (checkoutLoader) return checkoutLoader;
  checkoutLoader = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.onload = () => { clearTimeout(timer); if (window.Razorpay) resolve(); else fail(); };
    const fail = () => { clearTimeout(timer); script.remove(); checkoutLoader = null; reject(new Error('Secure checkout could not load. Check your connection and try again.')); };
    script.onerror = fail;
    const timer = setTimeout(fail, 20000);
    document.head.append(script);
  });
  return checkoutLoader;
}
async function verify() {
  if (!pending || busy) return;
  busy = true; controls();
  showMessage('Confirming your payment. Please keep this page open.', 'info');
  try {
    const result = await post('/api/wallet/verify', pending);
    byId('wallet-balance').textContent = money(result.balancePaise);
    remember(null);
    showMessage(result.alreadyCredited ? 'This payment is already credited to your wallet.' : 'Payment confirmed. Money has been added to your wallet.', 'success');
    try { await refresh(); } catch { /* Keep the verified balance and success message if history is unavailable. */ }
  } catch (error) {
    showMessage(`${error.message} You can retry verification safely; do not make another payment for this recharge.`);
  } finally { busy = false; controls(); }
}
for (const button of document.querySelectorAll('[data-amount]')) {
  button.addEventListener('click', () => {
    byId('recharge-amount').value = button.dataset.amount;
    updateSelection();
  });
}
function updateSelection() {
  for (const button of document.querySelectorAll('[data-amount]'))
    button.setAttribute('aria-pressed', String(Number(button.dataset.amount) === Number(byId('recharge-amount').value)));
}
byId('recharge-amount').addEventListener('input', updateSelection);
byId('retry-verification').addEventListener('click', verify);
byId('refresh-wallet').addEventListener('click', async () => {
  const button = byId('refresh-wallet'); button.disabled = true;
  try { await refresh(); } catch (error) { showMessage(error.message); }
  finally { button.disabled = false; }
});
byId('recharge-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || pending || !wallet?.rechargeEnabled || !byId('recharge-form').reportValidity()) return;
  const amount = byId('recharge-amount').value.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(amount)) return showMessage('Enter an amount with up to two decimal places.');
  busy = true; controls(); showMessage('Opening secure checkout...', 'info');
  let completed = false;
  try {
    await loadCheckout();
    const order = await post('/api/wallet/orders', { amount });
    const checkout = new window.Razorpay({
      key: order.keyId, order_id: order.orderId, amount: order.amountPaise, currency: order.currency,
      name: 'Launchpad', description: 'Wallet recharge', prefill: { name: user.name || '', email: user.email || '' },
      theme: { color: '#402b53' },
      handler: response => {
        completed = true;
        remember({ orderId: response.razorpay_order_id, paymentId: response.razorpay_payment_id,
          signature: response.razorpay_signature, amount: (order.amountPaise / 100).toFixed(2) });
        busy = false;
        verify();
      },
      modal: { ondismiss: () => {
        if (completed) return;
        busy = false; controls();
        showMessage('Checkout closed. If money was debited, do not pay again. Contact support if your balance remains unchanged.', 'info');
        refresh().catch(() => {});
      } }
    });
    checkout.on('payment.failed', response => {
      showMessage('Payment failed. Your wallet has not been credited for this attempt. You can retry in Checkout.');
      const paymentId = response?.error?.metadata?.payment_id;
      if (paymentId) post('/api/wallet/payment-status', { orderId: order.orderId, paymentId })
        .catch(() => { /* Unknown outcomes remain pending; the browser cannot assert a failed status. */ });
    });
    checkout.open();
  } catch (error) { busy = false; controls(); showMessage(error.message); }
});
async function start() {
  try {
    user = await request('/api/auth/me');
    storageKey = `launchpad-recharge-${user.uid}`;
    try { pending = JSON.parse(sessionStorage.getItem(storageKey)); } catch { pending = null; }
    await refresh();
    if (pending) await verify();
  } catch (error) {
    if (error.status === 401) return location.replace('/login');
    showMessage(error.message);
    byId('history-empty').textContent = 'Unable to load transactions. Refresh to try again.';
  }
}
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && user && !busy) refresh().catch(error => showMessage(error.message));
});
start();
