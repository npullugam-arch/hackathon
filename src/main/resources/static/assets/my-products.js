import {watchWithdrawals} from './withdrawal-live.js';
import {request,post,showMessage} from './api.js';
import {element,image} from './catalog-ui.js';
import {money,button,badge} from './commerce-ui.js';
import {claimStatus} from './earning-cycle-ui.js';
import {resumePurchase,checkoutBusy} from './purchase-checkout.js';
const $=id=>document.getElementById(id);let page=0,pages=0,loading=false,busy=false,displayed=[];
function controls(){$('my-previous').disabled=loading||busy||page===0;$('my-next').disabled=loading||busy||page+1>=pages;$('my-refresh').disabled=loading||busy;for(const b of document.querySelectorAll('#my-products button'))b.disabled=busy||b.dataset.unavailable==='true';}
function failure(ex){if(ex.status===401)location.replace('/login');else showMessage(ex.message);}
async function action(work){if(busy)return;busy=true;controls();try{const result=await work();showMessage(result?.alreadyClaimed?'This daily income was already claimed.':result?.amountPaise?`${money(result.amountPaise)} added to Winning Cash.`:'Payment status checked.','success');await load();}catch(ex){failure(ex);}finally{busy=false;controls();}}
async function load(){if(loading)return;loading=true;controls();try{
  const q=new URLSearchParams({page});if($('my-status').value)q.set('status',$('my-status').value);const [data,wallet,withdrawals]=await Promise.all([request('/api/purchases?'+q),request('/api/wallet'),request('/api/withdrawals/dashboard')]);displayed=data.items.map(p=>({...p,offset:new Date(p.serverTime).getTime()-Date.now()}));pages=data.totalPages;page=data.page;$('my-recharge-balance').textContent=money(wallet.balancePaise);$('my-winning-cash').textContent=money(withdrawals.currentWinningPaise);$('my-withdrawable-cash').textContent=money(withdrawals.availableWinningPaise);$('my-products').replaceChildren();
  for(const p of data.items){const card=element('article','commerce-card compact-product');card.dataset.purchase=p.id;const body=element('div','card-body'),top=element('div','card-top');top.append(element('h2','',p.productTitle),badge(p.status));if(p.imageUrl){const thumb=image(p.imageUrl,p.productTitle,'purchase-thumbnail');top.prepend(thumb);}body.append(top);
    const summary=element('dl','commerce-stats');for(const [label,value]of[['Daily earning',money(p.minimumDailyIncomePaise)+' – '+money(p.maximumDailyIncomePaise)], ["Today's Profit",money(p.todayProfitPaise)],['Current Claim',money(p.claimablePaise)],['Total Earnings',money(p.claimedIncomePaise)]])detailStat(summary,label,value);
    body.append(summary);body.append(element('p','commerce-progress-label',`Claim progress: ${p.progressPercent}% | ${p.claimedDays} / ${p.durationDays} claims completed`));const progress=element('progress');progress.max=100;progress.value=p.progressPercent;progress.setAttribute('aria-label','Completed claims');progress.setAttribute('aria-valuetext',`${p.claimedDays} of ${p.durationDays} claims completed`);body.append(progress,element('small','muted',`${money(p.claimedIncomePaise)} claimed${p.status==='COMPLETED'&&p.claimedDays<p.durationDays?'; Earning period ended with unclaimed earnings':''}`));
    const actions=element('div','commerce-actions');const overview=element('a','button secondary','Overview');overview.href='/features/my-product/'+p.id;actions.append(overview);
    if(p.paymentStatus!=='PAID'){const resume=button('Resume checkout',()=>resumePurchase(p.id),'primary');resume.dataset.resume=p.id;actions.append(badge(p.paymentStatus),resume,button('Check payment status',()=>action(()=>post(`/api/purchases/${p.id}/reconcile`))));}
    else if(p.claimablePaise>0){const claim=button('Claim '+money(p.claimablePaise),()=>action(()=>post(`/api/purchases/${p.id}/claim`)),'primary');claim.dataset.claim=p.id;actions.append(claim);}
    if(p.paymentStatus==='PAID'&&p.claimablePaise===0){const unavailable=button(p.status==='COMPLETED'?'Completed':'Claimed today',()=>{});unavailable.disabled=true;unavailable.dataset.unavailable='true';actions.append(unavailable);}
    const status=element('p','commerce-progress-label');status.dataset.claimStatus=p.id;body.append(actions,status);card.append(body);$('my-products').append(card);
  }
  document.dispatchEvent(new CustomEvent('portfolio-updated',{detail:data}));updateStatuses();$('my-empty').hidden=data.items.length>0;$('my-page').textContent=`${data.total} purchases · Page ${pages?page+1:0} of ${pages}`;
}catch(ex){failure(ex);}finally{loading=false;$('my-loading').hidden=true;controls();}}
function updateStatuses(){for(const p of displayed){const node=document.querySelector(`[data-claim-status="${p.id}"]`);if(node)node.textContent=claimStatus(p,Date.now()+p.offset);if(p.dailyEarningCycle?.status==='ACTIVE'&&Date.now()+p.offset>=new Date(p.dailyEarningCycle.endsAt).getTime()){const claim=document.querySelector(`[data-claim="${p.id}"]`);if(claim)claim.disabled=true;if(!loading&&!busy)load();}}}
function detailStat(list,label,value){const group=element('div');group.append(element('dt','',label),element('dd','',value));list.append(group);}
$('my-refresh').addEventListener('click',load);$('my-status').addEventListener('change',()=>{page=0;load();});$('my-previous').addEventListener('click',()=>{page--;load();});$('my-next').addEventListener('click',()=>{page++;load();});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!busy&&!checkoutBusy())load();});setInterval(()=>{if(!document.hidden&&!busy&&!checkoutBusy())load();},10000);load();

watchWithdrawals(load, () => busy || loading || checkoutBusy());
