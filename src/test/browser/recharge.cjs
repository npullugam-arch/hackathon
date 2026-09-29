/* Run from project root after:
 * mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/wallet-classpath.txt -DincludeScope=test
 * Set PLAYWRIGHT_MODULE to an installed playwright or playwright-core module path if not locally installed.
 * node src/test/browser/recharge.cjs
 * Uses only a disposable database and simulated Firebase/Razorpay; never contacts a real payment account.
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { createHmac } = require('node:crypto');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const root = process.cwd();
const base = 'http://127.0.0.1:8096';
const sign = (body, secret = 'fixture-secret') => createHmac('sha256', secret).update(body).digest('hex');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  let portInUse = false;
  try { await fetch(base + '/login'); portInUse = true; } catch { }
  assert.equal(portInUse, false, 'Port 8096 must be free before starting the browser fixture');
  const classpath = ['target/test-classes', 'target/classes', fs.readFileSync('target/wallet-classpath.txt', 'utf8').trim()].join(path.delimiter);
  const log = fs.openSync('target/wallet-browser-server.log', 'w');
  const server = spawn(process.env.JAVA_BIN || 'java', ['-Xmx384m', '-cp', classpath, 'com.iare.hackathon.wallet.WalletBrowserFixture'], {
    cwd: root, windowsHide: true, stdio: ['pipe', log, log]
  });
  let browser;
  try {
    let ready = false;
    for (let i = 0; i < 150; i++) {
      if (server.exitCode !== null) throw new Error('Browser fixture exited; inspect target/wallet-browser-server.log');
      try { if ((await fetch(base + '/login')).ok) { ready = true; break; } } catch { }
      await delay(500);
    }
    assert.ok(ready, 'Browser fixture must start');
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    await context.addCookies([{ name: 'hackathon_session', value: 'wallet-browser-session', url: base }]);
    await context.route('https://checkout.razorpay.com/v1/checkout.js', route => route.fulfill({
      contentType: 'application/javascript',
      body: `window.Razorpay = class {
        constructor(options) { this.options = options; this.events = {}; window.testCheckout = this; }
        on(event, handler) { this.events[event] = handler; }
        open() { window.checkoutOpened = true; }
      };`
    }));
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/features/recharge');
    await page.waitForFunction(() => !document.getElementById('recharge-fields').disabled);
    assert.match(await page.locator('#wallet-balance').textContent(), /0\.00/);
    for (const width of [1440, 768, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `No overflow at ${width}px`);
      await page.screenshot({ path: `target/recharge-${width}.png`, fullPage: true });
    }
    await page.locator('[data-amount="200"]').click();
    assert.equal(await page.locator('#recharge-amount').inputValue(), '200');
    assert.equal(await page.locator('[data-amount="200"]').getAttribute('aria-pressed'), 'true');
    await page.locator('#recharge-amount').fill('123.45');

    async function checkout() {
      await page.evaluate(() => { window.checkoutOpened = false; });
      await page.locator('#pay-now').click();
      await page.waitForFunction(() => window.checkoutOpened);
      return page.evaluate(() => ({ order: window.testCheckout.options.order_id, amount: window.testCheckout.options.amount,
        key: window.testCheckout.options.key, currency: window.testCheckout.options.currency }));
    }
    async function paid(order, pending = false, forged = false) {
      const payment = order.replace('order_', 'pay_') + (pending ? 'Pending' : '');
      const signature = forged ? '0'.repeat(64) : sign(order + '|' + payment);
      await page.evaluate(response => window.testCheckout.options.handler(response), {
        razorpay_order_id: order, razorpay_payment_id: payment, razorpay_signature: signature
      });
      return { orderId: order, paymentId: payment, signature };
    }
    async function postApi(url, data) {
      const csrf = await (await context.request.get(base + '/api/auth/csrf')).json();
      return context.request.post(base + url, { data, headers: { [csrf.headerName]: csrf.token } });
    }
    const first = await checkout();
    assert.equal(first.amount, 12345); assert.equal(first.currency, 'INR'); assert.equal(first.key, 'rzp_test_fixture');
    // Browser success alone must not change the wallet before the backend verifies.
    assert.equal((await (await context.request.get(base + '/api/wallet')).json()).balancePaise, 0);
    const firstResponse = await paid(first.order);
    await page.waitForFunction(() => document.getElementById('message').textContent.includes('Payment confirmed'));
    assert.match(await page.locator('#wallet-balance').textContent(), /123\.45/);
    const replay = await postApi('/api/wallet/verify', { ...firstResponse, amount: '123.45', userId: 'someone-else' });
    assert.equal(replay.status(), 200); assert.equal((await replay.json()).alreadyCredited, true);
    assert.equal((await (await context.request.get(base + '/api/wallet')).json()).balancePaise, 12345);

    await page.locator('[data-amount="100"]').click();
    const second = await checkout();
    await paid(second.order, true);
    await page.waitForFunction(() => document.getElementById('message').textContent.includes('not confirmed as captured'));
    assert.equal(await page.locator('#retry-verification').isVisible(), true);
    assert.equal((await (await context.request.get(base + '/api/wallet')).json()).balancePaise, 12345);
    await page.reload();
    await page.waitForFunction(() => document.getElementById('message').textContent.includes('Payment confirmed'));
    assert.match(await page.locator('#wallet-balance').textContent(), /223\.45/);

    const failed = await checkout();
    assert.ok(failed.order);
    await page.evaluate(() => { window.testCheckout.events['payment.failed'](); window.testCheckout.options.modal.ondismiss(); });
    await page.waitForFunction(() => !document.getElementById('recharge-fields').disabled);
    assert.equal((await (await context.request.get(base + '/api/wallet')).json()).balancePaise, 22345);

    const forged = await checkout();
    await paid(forged.order, false, true);
    await page.waitForFunction(() => document.getElementById('message').textContent.includes('verification failed'));
    assert.equal((await (await context.request.get(base + '/api/wallet')).json()).balancePaise, 22345);
    await page.evaluate(() => sessionStorage.clear());

    const anonymous = await browser.newContext();
    assert.equal((await anonymous.request.get(base + '/api/wallet')).status(), 401);
    await page.reload();
    await page.waitForFunction(() => document.getElementById('wallet-balance').textContent.includes('223.45'));
    assert.equal(await page.locator('#history-rows tr').count(), 2);
    assert.equal((await (await context.request.get(base + '/api/wallet')).json()).balancePaise, 22345);
    assert.deepEqual(errors, []);
    console.log('PASS: responsive recharge at 320/390/768/1440px; preset/custom amounts; paise order; verified credit; replay protection; pending retry after reload; failure/cancellation/tampering; no browser errors.');
  } finally {
    if (browser) await browser.close();
    if (server.exitCode === null) {
      server.stdin.end('stop\n');
      await Promise.race([new Promise(resolve => server.once('exit', resolve)), delay(15000)]);
      if (server.exitCode === null) server.kill();
    }
    fs.closeSync(log);
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
