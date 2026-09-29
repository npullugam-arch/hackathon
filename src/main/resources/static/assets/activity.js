const byId = id => document.getElementById(id);
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
const integer = new Intl.NumberFormat('en-IN');
const money = new Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits:0});
let activeUsers = 3000 + Math.floor(Math.random() * 2001);
let claimed = 300000 + Math.floor(Math.random() * 200001);
let timer = 0;
let frame = 0;
let running = true;

function bounded(value, min, max, step) {
  return Math.max(min, Math.min(max, value + Math.floor((Math.random() * 2 - 1) * step)));
}
function render(users, profit, duration = 900) {
  cancelAnimationFrame(frame);
  const fromUsers = Number(byId('activity-users').dataset.value || users);
  const fromProfit = Number(byId('activity-claims').dataset.value || profit);
  const started = performance.now();
  const paint = now => {
    const progress = reduced.matches ? 1 : Math.min(1, (now - started) / duration);
    const ease = 1 - Math.pow(1 - progress, 3);
    const nextUsers = fromUsers + (users - fromUsers) * ease;
    const nextProfit = fromProfit + (profit - fromProfit) * ease;
    byId('activity-users').textContent = integer.format(Math.round(nextUsers));
    byId('activity-claims').textContent = money.format(Math.round(nextProfit));
    byId('activity-users').dataset.value = nextUsers;
    byId('activity-claims').dataset.value = nextProfit;
    if (progress < 1 && running) frame = requestAnimationFrame(paint);
  };
  frame = requestAnimationFrame(paint);
}
function schedule() {
  window.clearTimeout(timer);
  if (!running) return;
  timer = window.setTimeout(() => { refresh(); schedule(); }, 5000);
}
function refresh() {
  if (!running || document.hidden) return;
  activeUsers = bounded(activeUsers, 3000, 5000, 180);
  claimed = bounded(claimed, 300000, 500000, 18000);
  render(activeUsers, claimed);
}
function setDemoCopy() {
  byId('activity-mode').textContent = 'PLATFORM ACTIVITY · DEMO';
  byId('activity-mode').classList.add('simulated');
  byId('activity-notice').textContent = 'DEMO ONLY: Illustrative values for interface preview. They do not represent real users, transactions or guaranteed earnings.';
  byId('activity-users-note').textContent = 'Illustrative sample range; not a count of real people online.';
  byId('activity-claims-note').textContent = 'Illustrative sample amount; not real claims, profit or guaranteed earnings.';
  byId('activity-updated').textContent = 'Illustrative values refresh while this page is open.';
  byId('activity-error').hidden = true;
  byId('activity-retry').hidden = true;
}
function stop() { running = false; window.clearTimeout(timer); cancelAnimationFrame(frame); }
setDemoCopy();
byId('activity-retry').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => { if (document.hidden) { cancelAnimationFrame(frame); } else { refresh(); schedule(); } });
window.addEventListener('pagehide', stop, {once:true});
render(activeUsers, claimed, 0); schedule();

