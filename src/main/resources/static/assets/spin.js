import {request, post, showMessage} from './api.js';
import {watchWithdrawals} from './withdrawal-live.js';
const $ = id => document.getElementById(id);
const money = value => new Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits:2}).format(value / 100);
const prizeMoney = value => new Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits:0}).format(value / 100);
const wheelPrizeMoney = value => { const rupees = value / 100; if (rupees >= 100000) return '\u20b91 lakh'; if (rupees >= 75000) return '\u20b975K'; if (rupees >= 10000) return '\u20b910K'; if (rupees >= 500) return '\u20b9500'; return prizeMoney(value); };
const date = value => new Date(value).toLocaleString('en-IN', {timeZone:'Asia/Kolkata', dateStyle:'medium', timeStyle:'short'});
let state, loading = false, busy = false, pending, storageKey, observedAt = 0, serverAt = 0, rotation = 0, chartKey = '', retryAt = 0;
function element(tag, text, className) {
  const node = document.createElement(tag); if (text !== undefined) node.textContent = text;
  if (className) node.className = className; return node;
}
function serverNow() { return serverAt + performance.now() - observedAt; }
function readPending() {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey));
    pending = /^[0-9a-f-]{36}$/i.test(value?.requestId || '') && /^\d{4}-\d{2}-\d{2}$/.test(value?.spinDay || '') ? value : null;
  } catch { pending = null; }
}
function clearPending() {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey));
    if (saved?.requestId === pending?.requestId) localStorage.removeItem(storageKey);
  } catch { /* Database daily uniqueness still protects retries if local storage is unavailable. */ }
  pending = null;
}
function controls() {
  const expired = state && serverNow() >= Date.parse(state.resetsAt);
  $('spin-button').disabled = busy || loading || !state || (!pending && (!state.eligible || expired));
  $('spin-button').textContent = busy ? 'Confirming your reward…' : !state ? 'Loading eligibility…' : pending ? 'Recover spin result' : state.eligible ? 'Spin & Earn' : 'Today’s spin completed';
  $('spin-refresh').disabled = busy || loading;
  if (busy) $('spin-status').textContent = 'Spinning…';
  else if (state) $('spin-status').textContent = state.eligible ? 'Ready to spin' : 'Completed today';
}
function draw(prizes) {
  const key = JSON.stringify(prizes); if (chartKey === key) return; chartKey = key;
  const group = $('spin-segments'); group.replaceChildren();
  const svg = (tag, attrs, text) => {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
    if (text !== undefined) node.textContent = text; return node;
  };
  const point = degrees => { const radians = degrees * Math.PI / 180; return [200 + 197 * Math.cos(radians), 200 + 197 * Math.sin(radians)].join(' '); };
  const angle = 360 / prizes.length;
  prizes.forEach((prize, index) => {
    const start = -90 - angle / 2 + index * angle, end = start + angle;
    group.append(svg('path', {d:`M200 200 L${point(start)} A197 197 0 0 1 ${point(end)} Z`, class:`spin-sector spin-sector-${index}`}));
    group.append(svg('text', {x:200, y:78, transform:`rotate(${index * angle} 200 200)`, class:`spin-prize-label ${index % 2 ? 'light' : ''}`}, wheelPrizeMoney(prize.amountPaise)));
  });
  $('spin-wheel-title').textContent = 'Available rewards: ' + prizes.map(p => prizeMoney(p.amountPaise)).join(', ') + '. Chances differ by prize.';
  $('spin-prizes').replaceChildren(...prizes.map(prize => {
    const chance = prize.weight * 100 / prize.totalWeight, item = element('li');
    item.append(element('strong', prizeMoney(prize.amountPaise)), element('span', chance < .01 ? '<0.01% chance' : chance.toLocaleString('en-IN', {maximumFractionDigits:2}) + '% chance'));
    return item;
  }));
}
function showReward(reward, repeated = false) {
  $('spin-result').hidden = !reward; if (!reward) return;
  $('spin-won').textContent = 'You Won ' + prizeMoney(reward.amountPaise);
  $('spin-result-note').textContent = repeated ? 'This reward was already credited to your Available Winning Cash. No additional credit was made.' : money(reward.amountPaise) + ' has been added to your Available Winning Cash.';
  $('spin-transaction').textContent = reward.id; $('spin-awarded-at').textContent = date(reward.awardedAt);
}
function render(value) {
  state = value; observedAt = performance.now(); serverAt = Date.parse(value.serverTime);
  storageKey = 'daily-spin-pending:' + value.userId; readPending();
  if (pending && value.todayReward && pending.spinDay === value.spinDay) {
    clearPending(); showMessage('Your reward was recovered and is credited to Winning Cash.', 'success');
  }
  $('spin-balance').textContent = money(value.availableWinningPaise);
  $('spin-status').textContent = value.eligible ? 'Ready to spin' : 'Completed today';
  $('spin-wheel-note').textContent = pending ? 'A previous spin needs confirmation. Recover its result safely.' : value.eligible ? 'Your daily spin is ready. Good luck!' : 'You’ve used today’s spin. Come back after midnight IST.';
  draw(value.prizes); showReward(value.todayReward, true);
  if (!busy) {
    const index = value.todayReward ? value.prizes.findIndex(prize => prize.amountPaise === value.todayReward.amountPaise) : 0;
    rotation = (360 - Math.max(0, index) * 360 / value.prizes.length) % 360;
    const wheel = $('spin-wheel');
    for (const animation of wheel.getAnimations()) animation.cancel();
    wheel.animate([{transform:`rotate(${rotation}deg)`}], {duration:0, fill:'forwards'});
  }
  $('spin-history').replaceChildren(...value.recentRewards.map(reward => {
    const row = element('tr'); row.append(element('td', money(reward.amountPaise)), element('td', reward.spinDay), element('td', date(reward.awardedAt)), element('td', reward.id)); return row;
  }));
  $('spin-history-empty').hidden = value.recentRewards.length > 0;
  $('spin-history-empty').textContent = 'No spins yet. Your first reward will appear here.';
  tick(); controls();
}
function failure(error) {
  if (error.status === 401) location.replace('/login');
  else showMessage(error.message || 'Unable to confirm your spin. Please try again.');
}
async function load() {
  if (loading || busy) return; loading = true; controls();
  try { render(await request('/api/spin')); }
  catch (error) { failure(error); if (!state) { $('spin-status').textContent = 'Temporarily unavailable'; $('spin-wheel-note').textContent = 'Use Refresh eligibility to try again.'; $('spin-history-empty').textContent = 'Reward history is temporarily unavailable.'; } }
  finally { loading = false; controls(); }
}
async function animateReward(reward, prizes) {
  const index = prizes.findIndex(prize => prize.amountPaise === reward.amountPaise); if (index < 0) return;
  const target = (360 - index * 360 / prizes.length) % 360;
  const next = rotation + 360 * 5 + ((target - rotation % 360 + 360) % 360);
  const wheel = $('spin-wheel');
  const animation = wheel.animate([{transform:`rotate(${rotation}deg)`}, {transform:`rotate(${next}deg)`}], {
    duration:matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 2800, easing:'cubic-bezier(.12,.65,.16,1)', fill:'forwards'
  });
  await animation.finished; rotation = next;
  // Keep only the completed animation; the result is always chosen by the server.
  for (const previous of wheel.getAnimations()) if (previous !== animation) previous.cancel();
}
$('spin-button').addEventListener('click', async () => {
  if (busy || loading || !state || (!state.eligible && !pending)) return;
  busy = true; controls(); showMessage('');
  let submitted;
  try {
    submitted = pending || {requestId:crypto.randomUUID(), spinDay:state.spinDay};
    try { localStorage.setItem(storageKey, JSON.stringify(submitted)); }
    catch { throw new Error('Browser storage is unavailable. Enable it so your spin result can be recovered safely.'); }
    pending = submitted;
    const result = await post('/api/spin', submitted);
    clearPending(); render(result.state); showReward(result.reward, result.alreadySpun);
    showMessage(result.alreadySpun ? 'Your existing reward has been recovered. No additional credit was made.' : 'You Won ' + prizeMoney(result.reward.amountPaise) + ' — credited to Winning Cash.', 'success');
    if (!result.alreadySpun) await animateReward(result.reward, result.state.prizes);
  } catch (error) {
    if ([400,404,409].includes(error.status)) clearPending();
    failure(error);
  } finally {
    busy = false; controls();
    // Reconcile even after a dropped response; the receipt, not the animation, confirms the reward.
    await load();
  }
});
function tick() {
  if (!state) return;
  const remaining = Math.max(0, Date.parse(state.resetsAt) - serverNow());
  if (remaining <= 0) {
    $('spin-countdown').textContent = 'Checking…'; controls();
    if (!busy && !loading && !document.hidden && performance.now() >= retryAt) { retryAt = performance.now() + 5000; load(); }
    return;
  }
  const seconds = Math.ceil(remaining / 1000), hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds % 3600 / 60);
  $('spin-countdown').textContent = state.eligible ? 'Ready now' : [hours, minutes, seconds % 60].map(value => String(value).padStart(2, '0')).join(':');
  $('spin-reset-note').textContent = 'Next daily reset: ' + date(state.resetsAt) + ' IST. One spin per day.';
}
$('spin-refresh').addEventListener('click', () => { showMessage(''); load(); });
window.addEventListener('storage', event => { if (event.key === storageKey) { readPending(); load(); } });
setInterval(tick, 1000);
watchWithdrawals(load, () => busy || loading);
load();
