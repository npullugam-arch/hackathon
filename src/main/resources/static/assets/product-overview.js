import {request,post,showMessage} from './api.js';
import {cycleText,claimStatus} from './earning-cycle-ui.js';
import {PerformanceChart} from './simulated-performance.js';
import {money,date,detail,claimsRows} from './commerce-ui.js';
const $=id=>document.getElementById(id), id=location.pathname.split('/').pop();
let purchase,loading=false,busy=false,serverOffset=0;
function failure(ex){if(ex.status===401)location.replace('/login');else showMessage(ex.message);}
async function load(){
  if(loading)return;loading=true;$('overview-refresh').disabled=true;
  try{
    const data=await request('/api/purchases/'+encodeURIComponent(id));purchase=data.purchase;const p=purchase;serverOffset=new Date(p.serverTime).getTime()-Date.now();
    $('overview-content').hidden=false;$('overview-name').textContent=p.productTitle;$('overview-status').textContent=p.status;
    $('today-profit').textContent="Today's Profit: "+money(p.todayProfitPaise);
    $('overview-range').textContent='Daily earning range: '+money(p.minimumDailyIncomePaise)+' – '+money(p.maximumDailyIncomePaise);
    $('overview-progress').value=p.progressPercent;
    $('overview-progress-text').textContent=`${p.completedDays} completed / ${p.remainingDays} remaining days · ${p.claimedDays} days claimed`;
    $('overview-totals').replaceChildren();
    for(const [k,v] of [['Purchase amount',money(p.purchaseAmountPaise)],['Current claim',money(p.claimablePaise)],['Total claimed',money(p.claimedIncomePaise)],['Remaining earning potential','Up to '+money(p.remainingEarningsPaise)]])detail($('overview-totals'),k,v);
    $('overview-info').replaceChildren();
    for(const [k,v] of [['Product',p.productTitle],['Purchase ID',p.id],['Purchased',date(p.purchaseDate)],['First claim available',date(p.startDate)],['Cycle ends',date(p.endDate)],['Duration',p.durationDays+' days'],['Claim schedule','12:00 AM Asia/Kolkata'],['Payment status',p.paymentStatus]])detail($('overview-info'),k,v);
    claimsRows($('overview-claims'),data.claims);$('overview-no-claims').hidden=data.claims.length>0;
    $('chart-actual').textContent=p.status==='COMPLETED'?(p.claimedDays===p.durationDays?'All claims completed. ':'Earning period ended. ')+'Actual backend-generated final daily profit: '+money(p.finalDailyProfitPaise)+'. Total actually claimed: '+money(p.claimedIncomePaise)+'.':"Actual backend-generated Today's Profit: "+money(p.todayProfitPaise)+'. The illustration does not change this amount.';
    renderCycle(p.dailyEarningCycle);availability();chart.refresh();
  }catch(ex){failure(ex);}finally{loading=false;$('overview-refresh').disabled=busy;}
}
function availability(){
  if(!purchase)return;const p=purchase;const seconds=p.nextClaimAt?Math.max(0,Math.ceil((new Date(p.nextClaimAt)-(Date.now()+serverOffset))/1000)):0;
  const rolledOver=p.dailyEarningCycle?.status==='ACTIVE'&&Date.now()+serverOffset>=new Date(p.dailyEarningCycle.endsAt).getTime();
  $('overview-claim').disabled=busy||p.claimablePaise<=0||rolledOver;
  $('overview-claim').textContent=p.claimablePaise>0?'Claim '+money(p.claimablePaise):'Claim unavailable';
  $('overview-availability').textContent=claimStatus(p,Date.now()+serverOffset);
  if(!busy&&!loading&&(rolledOver||(p.status==='ACTIVE'&&p.claimablePaise===0&&p.nextClaimAt&&seconds===0)))load();
}
$('overview-claim').addEventListener('click',async()=>{
  if(busy||!purchase||purchase.claimablePaise<=0)return;busy=true;availability();
  try{const result=await post('/api/purchases/'+encodeURIComponent(id)+'/claim');showMessage(result.alreadyClaimed?'Already claimed.':money(result.amountPaise)+' credited to Winning Cash.','success');await load();}catch(ex){failure(ex);await load();}finally{busy=false;$('overview-refresh').disabled=false;availability();}
});

function renderCycle(cycle){$('overview-cycle-amount').textContent='Cycle Progress: '+cycleText(cycle);$('overview-cycle-progress').value=cycle?.progressPercent||0;}
const chart=new PerformanceChart(id,data=>{
  serverOffset=new Date(data.serverTime).getTime()-Date.now();
  if(purchase&&data.cycle?.date!==purchase.dailyEarningCycle?.date){load();return;}
  renderCycle(data.cycle);
},failure);
$('overview-refresh').addEventListener('click',load);document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!busy)load();});
setInterval(availability,1000);setInterval(()=>{if(!document.hidden&&purchase)chart.refresh();},5000);
setInterval(()=>{if(!document.hidden&&!busy)load();},30000);load();
