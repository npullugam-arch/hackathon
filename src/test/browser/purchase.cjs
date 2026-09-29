// Real HTTP, templates, services and disposable PostgreSQL; no production payments.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawn}=require('node:child_process');
const base='http://127.0.0.1:8097',delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  let inUse=false;try{await fetch(base+'/login');inUse=true;}catch{}assert.equal(inUse,false,'Port 8097 must be free');
  const log=fs.openSync('target/purchase-browser-server.log','w');
  const cp=['target/test-classes','target/classes',fs.readFileSync('target/wallet-classpath.txt','utf8').trim()].join(path.delimiter);
  const server=spawn(process.env.JAVA_BIN || 'java',['-Xmx384m','-cp',cp,'com.iare.hackathon.withdrawal.WithdrawalBrowserFixture','purchase-flow'],{windowsHide:true,stdio:['pipe',log,log]});
  let browser;
  try{
    let ready=false;for(let i=0;i<240;i++){if(server.exitCode!==null)throw Error('Fixture exited; see target/purchase-browser-server.log');if(fs.readFileSync('target/purchase-browser-server.log','utf8').includes('WITHDRAWAL_BROWSER_READY')){ready=true;break;}await delay(500);}assert.ok(ready,'Fixture must start');
    browser=await chromium.launch({headless:true});const context=await browser.newContext();await context.addCookies([{name:'hackathon_session',value:'withdrawal-browser-session',url:base}]);
    const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',dialog=>{errors.push('Unexpected native dialog: '+dialog.type());dialog.dismiss();});
    async function layouts(target,prefix,widths=[1440,768,390,320]){for(const width of widths){await target.setViewportSize({width,height:950});assert.equal(await target.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`No overflow at ${width}px on ${prefix}`);await target.screenshot({path:`target/${prefix}-${width}.png`,fullPage:true});}}
    await page.goto(base+'/products');await page.getByRole('button',{name:'BUY',exact:true}).waitFor();
    assert.equal(await page.locator('[data-end],.countdown,[data-catalog-end]').count(),0);
    const productId=await page.locator('[data-buy]').getAttribute('data-buy');
    const second=await browser.newContext();await second.addCookies([{name:'hackathon_session',value:'invitation-browser-invitee',url:base}]);
    const other=await second.newPage();await other.goto(base+'/products');await other.getByRole('button',{name:'BUY',exact:true}).waitFor();
    await page.getByRole('link',{name:/View Product/}).click();await page.getByRole('button',{name:'BUY',exact:true}).click();
    await page.waitForURL('**/features/my-product');await page.locator('[data-claim]').waitFor();assert.equal(await page.locator('.compact-product').count(),1);
    assert.match(await page.locator('#my-recharge-balance').textContent(),/750/);
    const progress=page.getByRole('progressbar',{name:'Completed claims'});
    assert.equal(await progress.getAttribute('value'),'0');
    await page.locator('[data-claim]').click();
    await page.waitForFunction(()=>document.querySelector('progress')?.value===2.5);
    assert.match(await page.locator('.commerce-progress-label').first().textContent(),/1 \/ 40 claims completed/);
    await page.reload();await page.waitForFunction(()=>document.querySelector('progress')?.value===2.5);
    await layouts(page,'my-products-claim-progress');

    await page.goto(base+'/products');await page.getByRole('button',{name:'SOLD',exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'SOLD',exact:true}).isDisabled(),true);
    await layouts(page,'purchased-catalog');await page.reload();await page.getByRole('button',{name:'SOLD',exact:true}).waitFor();
    await page.getByRole('link',{name:/View Product/}).click();await page.getByRole('button',{name:'SOLD',exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'SOLD',exact:true}).isDisabled(),true);
    const statuses=await page.evaluate(async productId=>{const {request}=await import('/assets/api.js');const csrf=await request('/api/auth/csrf');return Promise.all([1,2].map(async()=>{try{await request('/api/purchases',{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify({productId})});return 200;}catch(ex){return ex.status;}}));},productId);assert.deepEqual(statuses,[409,409]);
    assert.equal((await (await context.request.get(base+'/api/wallet')).json()).balancePaise,75000);
    await other.reload();await other.getByRole('button',{name:'BUY',exact:true}).waitFor();assert.equal(await other.getByRole('button',{name:'BUY',exact:true}).isEnabled(),true);
    await other.getByRole('button',{name:'BUY',exact:true}).click();await other.waitForURL('**/features/my-product');await other.locator('[data-claim]').waitFor();assert.equal(await other.locator('.compact-product').count(),1);
    await other.goto(base+'/products');await other.getByRole('button',{name:'SOLD',exact:true}).waitFor();assert.equal(await other.getByRole('button',{name:'SOLD',exact:true}).isDisabled(),true);
    assert.equal((await (await second.request.get(base+'/api/wallet')).json()).balancePaise,75000);
    const admin=await browser.newContext(),adminPage=await admin.newPage();adminPage.on('pageerror',e=>errors.push(e.message));
    await adminPage.goto(base+'/admin');await adminPage.locator('#email').fill('admin@example.invalid');await adminPage.locator('#password').fill('browser-test-password');await adminPage.locator('#sign-in').click();await adminPage.waitForURL('**/admin/dashboard');
    const existingCard=adminPage.locator('#admin-products .product-card').filter({hasText:'Single purchase product'});
    await existingCard.getByRole('button',{name:'Edit',exact:true}).click();
    assert.equal(await adminPage.locator('#product-form input[type="datetime-local"],#product-form [name="durationDays"],#end-date').count(),0);
    assert.equal(await adminPage.locator('[name="totalClaims"]').inputValue(),'40');
    await adminPage.locator('#product-form [name="title"]').fill('Updated product');
    await adminPage.locator('#product-form button[type="submit"]').click();await adminPage.waitForFunction(()=>!document.getElementById('product-dialog').open);
    const updatedCard=adminPage.locator('#admin-products .product-card').filter({hasText:'Updated product'});
    await updatedCard.getByRole('button',{name:'Stop',exact:true}).click();await updatedCard.getByText('Stopped',{exact:true}).waitFor();
    assert.equal((await (await admin.request.get(base+'/api/admin/products')).json()).find(p=>p.id===productId).active,false);
    await updatedCard.getByRole('button',{name:'Activate',exact:true}).click();await updatedCard.getByText('Active',{exact:true}).waitFor();
    await adminPage.locator('#add-product').click();
    for(const [field,value] of Object.entries({title:'No timer product',imageUrl:'https://example.invalid/new.png',originalPrice:'100',discountPrice:'100',totalClaims:'5',minimumDailyIncome:'1',maximumDailyIncome:'2',description:'No dates required'}))await adminPage.locator(`#product-form [name="${field}"]`).fill(value);
    await adminPage.locator('#product-form [name="active"]').check();await adminPage.locator('#product-form button[type="submit"]').click();await adminPage.waitForFunction(()=>!document.getElementById('product-dialog').open);
    await adminPage.locator('#admin-products .product-card').filter({hasText:'No timer product'}).waitFor();
    await admin.close();
    assert.deepEqual(errors,[]);console.log('PASS: BUY to SOLD, listing/detail/reload, duplicate direct API rejection, one debit, My Products, independent second user, claim progress, timer-free admin create/edit/stop/activate, desktop/mobile.');
    await second.close();
  }finally{if(browser)await browser.close();server.stdin.end('\n');await Promise.race([new Promise(r=>server.once('exit',r)),delay(15000)]);if(server.exitCode===null)server.kill();fs.closeSync(log);}
})().catch(error=>{console.error(error);process.exitCode=1;});
