// Frontend integration: real template/assets, no financial services or database.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const root = path.resolve(__dirname, '../../main/resources');
(async () => {
  const mutations = [], errors = [];
  const navigation = fs.readFileSync(path.join(root, 'templates/fragments/navigation.html'), 'utf8');
  const html = fs.readFileSync(path.join(root, 'templates/calculator.html'), 'utf8')
    .replace('<header th:replace="~{fragments/navigation :: navigation}"></header>', navigation);
  const server = http.createServer((req, res) => {
    if (req.method !== 'GET') mutations.push(req.method + ' ' + req.url);
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'");
    if (req.url === '/features/calculator') { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(html); }
    else if (req.url === '/api/advertisements/current') { res.setHeader('Content-Type', 'application/json'); res.end('{"items":[]}'); }
    else if (/^\/assets\/[a-zA-Z0-9._-]+$/.test(req.url)) {
      const file = path.join(root, 'static', req.url);
      if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
      res.setHeader('Content-Type', req.url.endsWith('.js') ? 'text/javascript' : req.url.endsWith('.css') ? 'text/css' : 'image/svg+xml');
      res.end(fs.readFileSync(file));
    } else { res.writeHead(404); res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({headless: true});
    const page = await browser.newPage();
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    page.on('dialog', dialog => { errors.push('Native dialog: ' + dialog.type()); dialog.dismiss(); });
    await page.goto(`http://127.0.0.1:${server.address().port}/features/calculator`);
    assert.equal(await page.locator('#calc-results').isVisible(), false);
    assert.match(await page.locator('#calc-empty').textContent(), /four simple inputs/);
    await page.locator('#calc-example').click();
    await page.locator('#calc-results').waitFor({state: 'visible'});
    assert.equal(await page.locator('#calc-net').textContent(), '-₹100.00');
    assert.equal(await page.locator('#calc-roi').textContent(), '-10.00%');
    assert.equal(await page.locator('#calc-break-even').textContent(), '34 days');
    assert.match(await page.locator('#calc-break-even-context').textContent(), /Beyond the 30-day duration/);
    assert.match(await page.locator('#risk-description').textContent(), /₹400.00/);
    assert.equal(await page.locator('#scenario-avg-total').textContent(), '₹900.00');
    assert.equal(await page.locator('#calc-chart rect').count(), 6);
    assert.equal((await page.locator('#calc-announcement').boundingBox()).width, 1);
    fs.mkdirSync(path.resolve('target'), {recursive: true});
    for (const width of [1440, 1024, 768, 390, 320]) {
      await page.setViewportSize({width, height: 1000});
      await page.evaluate(() => window.scrollTo(0, 0));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `Page overflow at ${width}`);
      await page.screenshot({path: `target/profit-calculator-${width}.png`, fullPage: true});
    }
    await page.locator('#calc-maximum').fill('10');
    assert.equal(await page.locator('#calc-results').isVisible(), false);
    assert.equal(await page.locator('#calc-maximum').getAttribute('aria-invalid'), 'true');
    assert.match(await page.locator('#maximum-error').textContent(), /at least the minimum/);
    await page.locator('#calc-minimum').fill('0'); await page.locator('#calc-maximum').fill('0');
    assert.equal(await page.locator('#calc-roi').textContent(), '-100.00%');
    assert.equal(await page.locator('#calc-break-even').textContent(), 'Not reached');
    assert.match(await page.locator('#risk-title').textContent(), /all entered scenarios/);
    await page.locator('#calc-price').fill('0'); assert.equal(await page.locator('#calc-results').isVisible(), false);
    await page.locator('#calc-price').fill('0.30'); await page.locator('#calc-minimum').fill('0.01'); await page.locator('#calc-maximum').fill('0.02');
    assert.equal(await page.locator('#scenario-avg-total').textContent(), '₹0.45');
    await page.locator('#calc-days').fill('1.5'); assert.equal(await page.locator('#calc-results').isVisible(), false);
    await page.locator('#calc-days').fill('36500');
    for (const field of ['price', 'minimum', 'maximum']) await page.locator('#calc-' + field).fill('9999999.99');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'Large values must wrap');
    assert.equal(await page.locator('#calc-results').isVisible(), true);
    await page.getByRole('button', {name: 'Reset', exact: true}).click();
    await page.waitForFunction(() => document.getElementById('calc-results').hidden);
    for (const field of ['price', 'minimum', 'maximum', 'days']) assert.equal(await page.locator('#calc-' + field).inputValue(), '');
    assert.equal(await page.locator('.calc-error:visible').count(), 0);
    assert.equal(await page.locator('#calc-price').evaluate(el => el === document.activeElement), true);
    assert.deepEqual(mutations, []); assert.deepEqual(errors, []);
    console.log('PASS: live calculations, loss exposure, zero income, precision, invalid/empty/reset states, chart, CSP, five responsive widths, and zero financial mutations.');
  } finally { if (browser) await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
