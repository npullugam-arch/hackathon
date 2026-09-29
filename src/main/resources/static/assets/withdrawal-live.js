export function watchWithdrawals(refresh, blocked = () => false) {
  let stream, pending = false, running = false;
  async function drain() {
    if (!pending || running || document.hidden || blocked()) return;
    pending = false; running = true;
    try { await refresh(); } finally { running = false; }
  }
  function changed() { pending = true; drain(); }
  function connect() {
    if (!stream && !document.hidden) {
      stream = new EventSource('/api/withdrawals/events');
      stream.addEventListener('withdrawal-change', changed);
      stream.onopen = changed;
    }
  }
  function close() { stream?.close(); stream = null; }
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) close(); else { connect(); changed(); }
  });
  window.addEventListener('pagehide', close);
  window.addEventListener('pageshow', () => { connect(); changed(); });
  setInterval(drain, 250);
  // Reconcile missed events and changes committed on another application instance.
  setInterval(changed, 5000);
  connect();
}
