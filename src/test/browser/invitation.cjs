// Uses the existing disposable PostgreSQL/HTTP fixture. No live identity or payment services.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawn}=require('node:child_process');
const base='http://127.0.0.1:8097',delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  let inUse=false;try{await fetch(base+'/login');inUse=true;}catch{}assert.equal(inUse,false,'Port 8097 must be free');
  const log=fs.openSync('target/invitation-browser-server.log','w');
  const cp=['target/test-classes','target/classes',fs.readFileSync('target/wallet-classpath.txt','utf8').trim()].join(path.delimiter);
  const server=spawn(process.env.JAVA_BIN || 'java',['-Xmx384m','-cp',cp,'com.iare.hackathon.withdrawal.WithdrawalBrowserFixture'],{windowsHide:true,stdio:['pipe',log,log]});
  let browser;
  try{
    let ready=false;for(let i=0;i<180;i++){if(server.exitCode!==null)throw Error('Fixture exited; see target/invitation-browser-server.log');if(fs.readFileSync('target/invitation-browser-server.log','utf8').includes('WITHDRAWAL_BROWSER_READY')){ready=true;break;}await delay(500);}assert.ok(ready,'Fixture must start');
    browser=await chromium.launch({headless:true});
    const inviter=await browser.newContext();await inviter.addCookies([{name:'hackathon_session',value:'invitation-browser-inviter',url:base}]);
    const first=await inviter.newPage();await first.goto(base+'/features/invitation');await first.waitForFunction(()=>/^[A-F0-9]{32}$/.test(document.getElementById('invitation-code').textContent));
    const code=await first.locator('#invitation-code').textContent();
    const context=await browser.newContext();await context.addCookies([{name:'hackathon_session',value:'invitation-browser-invitee',url:base}]);
    await context.route('https://example.invalid/inviter.png',route=>route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=','base64')}));
    const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',dialog=>{errors.push('Unexpected browser '+dialog.type());dialog.dismiss();});
    await page.goto(base+'/features/invitation');await page.waitForFunction(()=>!document.getElementById('bind-button').disabled);assert.equal(await page.locator('#inviter-card').isVisible(),false);
    await page.locator('#inviter-code').fill('F'.repeat(32));await page.locator('#bind-button').click();await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();
    await page.locator('#invitation-toast.error.visible').waitFor();assert.match(await page.locator('#invitation-toast').textContent(),/Invalid invitation code/);assert.equal(await page.locator('#message').isVisible(),false);
    await page.waitForFunction(()=>!document.getElementById('bind-button').disabled);await page.locator('#inviter-code').fill(code);await page.locator('#bind-button').click();await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();
    await page.locator('#inviter-card').waitFor();assert.equal(await page.locator('#invitation-toast').textContent(),'Invitation code saved successfully.');
    assert.match(await page.locator('#inviter-card').textContent(),/You were invited byJohn DoeID: invite-browser-inviter/);assert.equal(await page.locator('#bind-invitation').isVisible(),false);assert.equal(await page.locator('#message').isVisible(),false);
    await page.waitForFunction(()=>document.querySelector('#inviter-card img')?.naturalWidth>0);
    // Extra client profile fields cannot change the profile read from the persisted relationship.
    const saved=await page.evaluate(async code=>{const {post}=await import('/assets/api.js');return post('/api/invitations/bind',{code,name:'Forged name',userId:'wrong-user',photoUrl:'https://wrong.invalid/avatar.png'});},code);
    assert.deepEqual(saved.inviter,{userId:'invite-browser-inviter',name:'John Doe',photoUrl:'https://example.invalid/inviter.png'});
    await page.reload();await page.locator('#inviter-card').waitFor();assert.match(await page.locator('#inviter-card h3').textContent(),/John Doe/);assert.equal(await page.locator('#bind-invitation').isVisible(),false);
    for(const width of [1440,768,390,320]){await page.setViewportSize({width,height:950});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`No invitation overflow at ${width}px`);await page.screenshot({path:`target/invitation-${width}.png`,fullPage:true});}
    await first.reload();await first.waitForFunction(()=>document.getElementById('invite-total').textContent==='1');assert.match(await first.locator('#invite-rows').textContent(),/Jane Doe/);
    assert.deepEqual(errors,[]);console.log('PASS: two-user referral, error/success toasts, trusted inviter name/photo/ID, forged profile ignored, refresh persistence, reciprocal history and responsive layouts.');
  }finally{if(browser)await browser.close();server.stdin.end('\n');if(server.exitCode===null)await Promise.race([new Promise(r=>server.once('exit',r)),delay(15000)]);if(server.exitCode===null)server.kill();fs.closeSync(log);}
})().catch(error=>{console.error(error);process.exitCode=1;});
