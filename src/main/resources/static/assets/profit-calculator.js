import {validateInputs, calculateProfit} from './profit-calculator-math.js';
const $ = id => document.getElementById(id), form = $('profit-form');
const fields = ['price', 'minimum', 'maximum', 'days'], touched = new Set();
const currency = new Intl.NumberFormat('en-IN', {style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2});
const percent = new Intl.NumberFormat('en-IN', {maximumFractionDigits: 2, minimumFractionDigits: 2});
const integer = new Intl.NumberFormat('en-IN', {maximumFractionDigits: 0});
const compact = new Intl.NumberFormat('en-IN', {style: 'currency', currency: 'INR', notation: 'compact', maximumFractionDigits: 1});
const money = paise => currency.format(Math.abs(paise) < 0.5 ? 0 : paise / 100);
const labels = ['Minimum', 'Average', 'Maximum'], keys = ['min', 'avg', 'max'];
let current, announcementTimer;
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}
function breakEven(scenario, days) {
  if (scenario.breakEven === null) return 'Not reached · zero income';
  if (scenario.breakEven > days) return 'Not reached within duration';
  return `Day ${integer.format(scenario.breakEven)}`;
}
function announce(text) {
  clearTimeout(announcementTimer);
  announcementTimer = setTimeout(() => { $('calc-announcement').textContent = text; }, 350);
}
function renderScenarios(result) {
  $('calc-scenarios').replaceChildren(...result.scenarios.map((scenario, index) => {
    const card = node('article', undefined, `calc-scenario ${keys[index]}`);
    card.append(node('h3', `${labels[index]} scenario`));
    const list = node('dl');
    for (const [label, value, suffix] of [
      ['Daily income', money(scenario.daily), 'daily'], ['Total earnings', money(scenario.total), 'total'],
      ['Net profit / loss', money(scenario.net), 'net'], ['ROI', `${percent.format(scenario.roi)}%`, 'roi'],
      ['Break-even', breakEven(scenario, result.days), 'break-even']
    ]) {
      const group = node('div'), term = node('dt', label), amount = node('dd', value);
      amount.id = `scenario-${keys[index]}-${suffix}`;
      if (['net', 'roi'].includes(suffix)) amount.dataset.outcome = scenario.net < 0 ? 'loss' : 'gain';
      group.append(term, amount); list.append(group);
    }
    const recovery = node('p', `${percent.format(scenario.recovery)}% of cost covered`, 'calc-recovery-label');
    const progress = node('progress'); progress.max = 100; progress.value = Math.min(100, scenario.recovery);
    progress.setAttribute('aria-label', `${labels[index]} scenario: ${percent.format(scenario.recovery)}% of purchase cost covered`);
    card.append(list, recovery, progress); return card;
  }));
}
function drawChart() {
  if (!current) return;
  const svg = $('calc-chart'), content = $('chart-content');
  const width = Math.max(220, svg.getBoundingClientRect().width), left = 63, right = width - 15, plot = right - left;
  svg.setAttribute('viewBox', `0 0 ${width} 240`); content.replaceChildren();
  const maximum = Math.max(current.price, ...current.scenarios.map(s => s.total));
  function shape(tag, attrs, text) {
    const item = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [key, value] of Object.entries(attrs)) item.setAttribute(key, String(value));
    if (text !== undefined) item.textContent = text;
    content.append(item); return item;
  }
  current.scenarios.forEach((scenario, index) => {
    const y = 51 + index * 52;
    shape('text', {x: 0, y: y + 19, class: 'calc-axis'}, ['Min', 'Avg', 'Max'][index]);
    shape('rect', {x: left, y, width: plot, height: 28, rx: 6, class: 'calc-chart-track'});
    const bar = shape('rect', {x: left, y, width: plot * scenario.total / maximum, height: 28, rx: 6, class: `calc-bar-${keys[index]}`});
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); title.textContent = `${labels[index]} total earnings: ${money(scenario.total)}`; bar.append(title);
  });
  const costX = left + plot * current.price / maximum;
  shape('line', {x1: costX, x2: costX, y1: 35, y2: 193, class: 'calc-cost-line'});
  shape('text', {x: Math.min(right, Math.max(left + 28, costX)), y: 21, 'text-anchor': 'end', class: 'calc-axis'}, 'Cost');
  shape('text', {x: left, y: 221, class: 'calc-axis'}, '₹0');
  shape('text', {x: right, y: 221, 'text-anchor': 'end', class: 'calc-axis'}, compact.format(maximum / 100));
  $('chart-description').textContent = `Purchase price ${money(current.price)}. ${current.scenarios.map((s, i) => `${labels[i]} total earnings ${money(s.total)}`).join('. ')}. Exact figures appear in the scenario cards.`;
}
function render(result) {
  const [minimum, average, maximum] = result.scenarios;
  $('calc-net').textContent = money(average.net);
  $('calc-net').parentElement.dataset.outcome = average.net < 0 ? 'loss' : 'gain';
  $('calc-net-context').textContent = `${money(average.total)} earnings − ${money(result.price)} purchase cost`;
  $('calc-roi').textContent = `${percent.format(average.roi)}%`;
  $('calc-roi').dataset.outcome = average.net < 0 ? 'loss' : 'gain';
  $('calc-break-even').textContent = average.breakEven === null ? 'Not reached' : `${integer.format(average.breakEven)} days`;
  $('calc-break-even-context').textContent = average.breakEven === null ? 'No recovery at zero daily income.' : average.breakEven > result.days ? `Beyond the ${integer.format(result.days)}-day duration; cost is not recovered in this scenario.` : `Cost recovered by day ${integer.format(average.breakEven)} at the average daily income.`;
  $('calc-risk').dataset.outcome = result.downside > 0 ? 'loss' : 'gain';
  $('risk-title').textContent = maximum.net < 0 ? 'Potential loss across all entered scenarios' : result.downside > 0 ? 'Potential downside in your income range' : 'Purchase cost covered in the entered scenarios';
  $('risk-description').textContent = result.downside > 0
    ? `At the minimum daily income, total earnings of ${money(minimum.total)} leave ${money(result.downside)} of the purchase price unrecovered. ${maximum.net < 0 ? `Even the maximum scenario leaves a ${money(-maximum.net)} shortfall.` : 'Higher daily income may cover the cost, but is not assured.'}`
    : 'The minimum scenario reaches or exceeds your purchase price. This is a calculation based on your inputs, not protection against actual losses.';
  $('calc-term').textContent = `Over ${integer.format(result.days)} days · average is the midpoint, not a guaranteed outcome.`;
  $('calc-total-range').textContent = `${money(minimum.net)} to ${money(maximum.net)}`;
  $('calc-daily-range').textContent = `${money(minimum.dailyNet)} to ${money(maximum.dailyNet)}`;
  renderScenarios(result); drawChart();
  announce(`Estimate updated. Average net ${average.net < 0 ? 'loss' : 'profit'} ${money(Math.abs(average.net))}. ROI ${percent.format(average.roi)} percent. ${breakEven(average, result.days)}.`);
}
function update() {
  const values = Object.fromEntries(new FormData(form)), {errors, value} = validateInputs(values);
  for (const field of fields) {
    const input = $(`calc-${field}`), message = $(`${field}-error`);
    const visible = !!errors[field] && (touched.has(field) || String(values[field]).trim() !== '');
    input.setAttribute('aria-invalid', String(visible)); message.hidden = !visible;
    message.textContent = visible ? errors[field] : '';
  }
  $('calc-results').hidden = !value; $('calc-empty').hidden = !!value;
  if (!value) {
    current = null; clearTimeout(announcementTimer);
    const hasErrors = fields.some(field => $(`calc-${field}`).getAttribute('aria-invalid') === 'true');
    $('empty-title').textContent = hasErrors ? 'Check your inputs' : 'Start with four simple inputs';
    $('empty-description').textContent = hasErrors ? 'Correct the highlighted fields to see your estimate. Previous results are hidden while inputs are incomplete or invalid.' : 'Your income scenarios, net profit and break-even estimate will appear here. Nothing is assumed until you enter your figures.';
    announce(hasErrors ? 'Estimate unavailable. Check the highlighted inputs.' : 'Enter all four inputs to see your estimate.');
    return;
  }
  current = calculateProfit(value); render(current);
}
form.addEventListener('input', event => { if (fields.includes(event.target.name)) touched.add(event.target.name); update(); });
form.addEventListener('focusout', event => { if (fields.includes(event.target.name)) { touched.add(event.target.name); update(); } });
form.addEventListener('submit', event => { event.preventDefault(); fields.forEach(field => touched.add(field)); update(); form.querySelector('[aria-invalid="true"]')?.focus(); });
form.addEventListener('reset', () => { touched.clear(); setTimeout(() => { update(); $('calc-price').focus(); }, 0); });
$('calc-example').addEventListener('click', () => {
  for (const [key, value] of Object.entries({price: '1000', minimum: '20', maximum: '40', days: '30'})) $(`calc-${key}`).value = value;
  touched.clear(); update();
});
new ResizeObserver(drawChart).observe($('calc-chart'));
update();
