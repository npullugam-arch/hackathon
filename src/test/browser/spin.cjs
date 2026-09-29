const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process');
const base='http://127.0.0.1:8098',delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
  let inUse=false;try{await fetch(base+'/login');inUse=true;}catch{}assert.equal(inUse,false,'Port 8098 must be free');
  const log=fs.openSync('target/spin-browser-server.log','w');
  const cp=['target/test-classes','target/classes',fs.readFileSync('target/wallet-classpath.txt','utf8').trim()].join(path.delimiter);
  const server=spawn(process.env.JAVA_BIN || 'java',['-Xmx384m','-cp',cp,'com.iare.hackathon.spin.SpinBrowserFixture'],{windowsHide:true,stdio:['pipe',log,log]});
  let browser;
  try{
    let ready=false;for(let i=0;i<240;i++){if(server.exitCode!==null)throw Error('Fixture exited; see target/spin-browser-server.log');if(fs.readFileSync('target/spin-browser-server.log','utf8').includes('SPIN_BROWSER_READY')){ready=true;break;}await delay(500);}assert.ok(ready);
    browser=await chromium.launch({headless:true});const context=await browser.newContext();
    await context.addCookies([{name:'hackathon_session',value:'spin-alice-session',url:base}]);
    const page=await context.newPage(),errors=[];
    page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>{errors.push('Native dialog '+d.type());d.dismiss();});
    await page.goto(base+'/features/renib-bonus');await page.waitForFunction(()=>!document.getElementById('spin-button').disabled);
    assert.deepEqual(await page.locator('#spin-prizes strong').allTextContents(),['₹1','₹2','₹5','₹10','₹20','₹30']);
    assert.equal(await page.locator('#spin-segments path').count(),6);
    for(const width of [1440,768,390,320]){await page.setViewportSize({width,height:1000});await page.evaluate(()=>scrollTo(0,0));assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`Overflow ${width}`);await page.screenshot({path:`target/spin-${width}.png`,fullPage:true});}
    const second=await context.newPage();await second.goto(base+'/features/renib-bonus');await second.waitForFunction(()=>!document.getElementById('spin-button').disabled);
    const withdrawal=await context.newPage();await withdrawal.goto(base+'/features/withdrawal');await withdrawal.waitForFunction(()=>document.getElementById('withdraw-available').textContent.includes('0.00'));
    await page.locator('#spin-button').click();await page.locator('#spin-result').waitFor({state:'visible'});
    const receipt=await page.evaluate(async()=>{const {request}=await import('/assets/api.js');return request('/api/spin');});
    assert.equal(receipt.eligible,false);assert.equal(receipt.recentRewards.length,1);assert.ok([100,200,500,1000,2000,3000].includes(receipt.todayReward.amountPaise));assert.equal(receipt.availableWinningPaise,receipt.todayReward.amountPaise);
    await second.waitForFunction(()=>document.getElementById('spin-button').textContent.includes('completed'));
    await withdrawal.waitForFunction(amount=>document.getElementById('withdraw-available').textContent===new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(amount/100),receipt.availableWinningPaise);
    const duplicate=await second.evaluate(async day=>{const {post}=await import('/assets/api.js');return post('/api/spin',{requestId:crypto.randomUUID(),spinDay:day,userId:'spin-bob',amountPaise:10000000});},receipt.spinDay);
    assert.equal(duplicate.alreadySpun,true);assert.equal(duplicate.reward.id,receipt.todayReward.id);assert.equal(duplicate.state.availableWinningPaise,receipt.availableWinningPaise);
    await page.reload();await page.waitForFunction(()=>document.getElementById('spin-history').children.length===1);assert.match(await page.locator('#spin-won').textContent(),/You Won/);
    await page.waitForFunction(amount=>{
      const matrix=new DOMMatrixReadOnly(getComputedStyle(document.getElementById('spin-wheel')).transform);
      const angle=(Math.round(Math.atan2(matrix.b,matrix.a)*180/Math.PI)+360)%360;
      return angle===(360-[100,200,500,1000,2000,3000].indexOf(amount)*60)%360;
    },receipt.todayReward.amountPaise);
    assert.match(await page.locator('#spin-countdown').textContent(),/^\d{2}:\d{2}:\d{2}$/);
    const before=await page.locator('#spin-countdown').textContent();await page.waitForFunction(old=>document.getElementById('spin-countdown').textContent!==old,before);
    await page.evaluate(async()=>{const {post}=await import('/assets/api.js');await post('/api/test/spin-before-midnight');});
    await page.locator('#spin-refresh').click();await page.waitForFunction(()=>!document.getElementById('spin-button').disabled,{},{timeout:12000});
    const next=await page.evaluate(async()=>{const {request}=await import('/assets/api.js');return request('/api/spin');});assert.notEqual(next.spinDay,receipt.spinDay);assert.equal(next.availableWinningPaise,receipt.availableWinningPaise);
    // The server commits, but the browser loses the response. A reload must recover the same receipt.
    let dropped=false;
    await page.route('**/api/spin',async route=>{if(route.request().method()==='POST'&&!dropped){dropped=true;await route.fetch();await route.abort('failed');}else await route.continue();});
    await page.locator('#spin-button').click();await page.waitForFunction(()=>document.querySelectorAll('#spin-history tr').length===2);
    await page.reload();await page.waitForFunction(()=>document.querySelectorAll('#spin-history tr').length===2);
    const final=await page.evaluate(async()=>{const {request}=await import('/assets/api.js');return request('/api/spin');});
    assert.equal(dropped,true);
    assert.equal(final.availableWinningPaise,final.recentRewards.reduce((sum,r)=>sum+r.amountPaise,0));assert.equal(final.eligible,false);
    await page.screenshot({path:'target/spin-won-320.png',fullPage:true});
    const bob=await browser.newContext({reducedMotion:'reduce'});await bob.addCookies([{name:'hackathon_session',value:'spin-bob-session',url:base}]);const bobPage=await bob.newPage();await bobPage.goto(base+'/features/renib-bonus');await bobPage.waitForFunction(()=>!document.getElementById('spin-button').disabled);await bobPage.locator('#spin-button').click();await bobPage.locator('#spin-result').waitFor({state:'visible'});
    const own=await bobPage.evaluate(async()=>{const {request}=await import('/assets/api.js');return request('/api/spin');});assert.equal(own.recentRewards.length,1);assert.equal(own.userId,'spin-bob');
    assert.deepEqual(errors,[]);console.log('PASS: six real prizes, server-selected credit, immediate Winning Cash refresh, multiple tabs/direct requests, IST midnight countdown, lost-response recovery, history, reduced motion and responsive layouts.');
  }finally{if(browser)await browser.close();server.stdin.end('\n');await Promise.race([new Promise(resolve=>server.once('exit',resolve)),delay(15000)]);if(server.exitCode===null)server.kill();fs.closeSync(log);}
})().catch(error=>{console.error(error);process.exitCode=1;});
