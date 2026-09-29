const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process');
const base='http://127.0.0.1:8097',delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 let portInUse=false;try{await fetch(base+'/login');portInUse=true;}catch{}assert.equal(portInUse,false,'Fixture port must be free');
 const log=fs.openSync('target/redesign-browser-server.log','w'),cp=['target/test-classes','target/classes',fs.readFileSync('target/wallet-classpath.txt','utf8').trim()].join(path.delimiter);
 const server=spawn('java',['-Xmx384m','-cp',cp,'com.iare.hackathon.withdrawal.RedesignBrowserFixture'],{windowsHide:true,stdio:['pipe',log,log]});let browser;
 try{
  for(let i=0;i<240;i++){if(server.exitCode!==null)throw Error('Fixture exited');if(fs.readFileSync('target/redesign-browser-server.log','utf8').includes('REDESIGN_BROWSER_READY'))break;await delay(500);}
  browser=await chromium.launch({headless:true});const context=await browser.newContext();await context.addCookies([{name:'hackathon_session',value:'withdrawal-browser-session',url:base}]);
  const page=await context.newPage(),errors=[],failedAssets=[];page.on('pageerror',e=>errors.push(e.message));page.on('response',r=>{if(r.url().includes('/assets/')&&!r.ok())failedAssets.push(r.url()+': '+r.status());});
  const sizes=[[1920,1080],[1440,900],[1280,720],[1024,768],[768,1024],[430,932],[390,844],[360,800]];
  const products=(await (await context.request.get(base+'/api/products')).json()).items;
  const purchased=(await (await context.request.get(base+'/api/purchases')).json()).items;
  const routes=[['home','/dashboard','#home-wallet-balance'],['my-products','/features/my-product','#portfolio-total'],['recharge','/features/recharge','#wallet-balance'],['withdrawal','/features/withdrawal','#withdraw-available'],['invitation','/features/invitation','#invite-total'],['products','/products','.product-card'],['product-details','/products/'+products[0].id,'.details-layout'],['profile','/profile','#dashboard-content'],['spin','/features/renib-bonus','#spin-status'],['team','/features/my-team','#team-total'],['salary','/features/monthly-salary','.salary-table'],['calculator','/features/calculator','#profit-form'],['support','/features/customer-service','#support-form'],['product-overview','/features/my-product/'+purchased[0].id,'#overview-content'],['photo','/tasks/take-photo','#photo-workspace'],['information','/information/privacy','.information-card']];
  const problems=[];
  for(const [name,url,ready]of routes){
   const response=await page.goto(base+url);assert.equal(response.status(),200,url);await page.locator(ready).first().waitFor({state:'visible'});await delay(600);
   for(const [width,height]of sizes){await page.setViewportSize({width,height});await delay(80);
    const layout=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,overflow:[...document.querySelectorAll('main *')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.right>innerWidth+1&&getComputedStyle(e).position!=='absolute'&&!e.closest('.commerce-scroll,.withdraw-scroll,.history-scroll,.team-tree,.salary-table-wrap,.spin-history-scroll');}).slice(0,5).map(e=>e.tagName+'.'+e.className)}));
    if(layout.scroll>width)problems.push({name,width,...layout});
    if(width===390||width===1440)await page.screenshot({path:`target/redesign-${name}-${width}.png`,fullPage:true});
    assert.equal(await page.locator('.mobile-bottom-nav').isVisible(),width<768);
   }
   const broken=await page.evaluate(()=>[...document.querySelectorAll('img')].filter(i=>!i.hidden&&i.complete&&i.currentSrc&&i.naturalWidth===0).map(i=>i.src));if(broken.length)problems.push({name,broken});
   console.log('Inspected',name,'at all 8 requested sizes');
  }
  fs.writeFileSync('target/redesign-layout-results.json',JSON.stringify({problems,errors,failedAssets},null,2));
  assert.deepEqual(problems,[],'No overflow or broken images');assert.deepEqual(errors,[],'No page exceptions');assert.deepEqual(failedAssets,[],'No missing assets');
  await page.goto(base+'/dashboard');await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.getElementById('home-wallet-balance').textContent.includes('600'));
  assert.ok((await page.locator('.wallet-actions').boundingBox()).y<500,'Wallet actions above the fold');
  await page.getByRole('button',{name:'Open menu',exact:true}).click();assert.equal(await page.locator('#app-sidebar').isVisible(),true);await page.keyboard.press('Escape');assert.equal(await page.locator('.menu-toggle').getAttribute('aria-expanded'),'false');
  await page.getByRole('button',{name:'Open menu',exact:true}).click();await page.locator('#main-navigation').getByRole('link',{name:'Customer Service',exact:true}).click();await page.waitForURL('**/features/customer-service');
  await page.locator('#support-search').fill('Razorpay');assert.ok(await page.locator('.faq-list details:visible').count()>0);await page.locator('#support-search').fill('no-matching-test-topic');assert.equal(await page.locator('#support-search-empty').isVisible(),true);
  await page.locator('#support-title').fill('Responsive UI test');await page.locator('#support-description').fill('Disposable support request used to verify the unchanged API.');await page.locator('#support-form button').click();await page.locator('.ticket-card').filter({hasText:'Responsive UI test'}).waitFor();
  await page.goto(base+'/features/my-product');await page.waitForFunction(()=>document.getElementById('portfolio-total').textContent==='2');assert.match(await page.locator('#portfolio-value').textContent(),/400/);await page.waitForFunction(()=>document.getElementById('chart-readout').textContent.includes('units'));
  await page.locator('#chart-style').selectOption('line');await page.locator('#chart-range').selectOption('6');await page.locator('#portfolio-product').selectOption({index:1});await page.locator('.portfolio-tabs a[href="#activity"]').click();assert.equal(await page.locator('.portfolio-tabs a[href="#activity"]').getAttribute('aria-current'),'page');
  await page.locator('.portfolio-tabs a[href="#products"]').click();const claim=page.locator('[data-claim]').first();if(await claim.count()){await claim.click();await page.waitForFunction(()=>[...document.querySelectorAll('.compact-product progress')].every(p=>p.value>0));}
  await page.goto(base+'/features/recharge');await page.locator('[data-amount="1000"]').click();assert.equal(await page.locator('#recharge-amount').inputValue(),'1000');
  await page.goto(base+'/profile');await page.locator('#dashboard-content').waitFor({state:'visible'});await page.locator('#logout').click();await page.waitForURL('**/?loggedOut=1');assert.equal((await context.request.get(base+'/api/auth/me')).status(),401);
  await page.goto(base+'/dashboard');await page.waitForURL('**/login');
  assert.deepEqual(errors,[]);console.log('PASS: 16 pages x 8 sizes; navigation, wallet, portfolio, charts, claims, support, recharge selection, profile, logout and auth boundary.');
 }finally{if(browser)await browser.close();server.stdin.end('\n');await Promise.race([new Promise(r=>server.once('exit',r)),delay(15000)]);if(server.exitCode===null)server.kill();fs.closeSync(log);}
})().catch(e=>{console.error(e);process.exitCode=1;});
