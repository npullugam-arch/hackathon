import {request} from './api.js';
import {PerformanceChart} from './simulated-performance.js';
import {money,claimsRows} from './commerce-ui.js';
const $=id=>document.getElementById(id);
let purchases=new Map(),selected='',chart,loading=false,lastSnapshot=0,selectionVersion=0;
function failure(error){if(error.status===401)return location.replace('/login');$('portfolio-error').textContent=error.message;$('portfolio-error').hidden=false;}
function summaries(){const paid=[...purchases.values()].filter(p=>p.paymentStatus==='PAID');
 $('portfolio-value').textContent=money(paid.reduce((n,p)=>n+p.purchaseAmountPaise,0));$('portfolio-active').textContent=paid.filter(p=>p.status==='ACTIVE').length;
 $('portfolio-today').textContent=money(paid.reduce((n,p)=>n+p.todayProfitPaise,0));$('portfolio-total').textContent=paid.length;
 const signature=paid.map(p=>p.id+':'+p.productTitle).join('|');
 if($('portfolio-product').dataset.signature!==signature){$('portfolio-product').replaceChildren(...paid.map(p=>{const o=document.createElement('option');o.value=p.id;o.textContent=p.productTitle;return o;}));$('portfolio-product').dataset.signature=signature;}
 $('portfolio-analytics').hidden=!paid.length;$('portfolio-chart-empty').hidden=paid.length>0;
 if(!paid.length)return;
 if(!purchases.has(selected)||!selected){selected=paid[0].id;$('portfolio-product').value=selected;selectProduct();}else $('portfolio-product').value=selected;
 const p=purchases.get(selected);$('chart-actual').textContent=`Today's generated income: ${money(p.todayProfitPaise)}. Actually claimed: ${money(p.claimedIncomePaise)}. The illustration does not change your earnings.`;
}
async function snapshot(){if(loading)return;loading=true;try{const first=await request('/api/purchases?size=100&page=0');const all=[...first.items];for(let page=1;page<first.totalPages;page++){const data=await request('/api/purchases?size=100&page='+page);all.push(...data.items);}purchases=new Map(all.map(p=>[p.id,p]));lastSnapshot=Date.now();$('portfolio-error').hidden=true;summaries();}catch(e){failure(e);}finally{loading=false;}}
async function history(){const version=++selectionVersion;try{const data=await request('/api/purchases/'+encodeURIComponent(selected));if(version!==selectionVersion)return;claimsRows($('portfolio-claims'),data.claims);$('portfolio-history-empty').hidden=data.claims.length>0;$('portfolio-history-empty').textContent='No claims yet for this product. Completed claims will appear here.';}catch(e){if(version===selectionVersion)failure(e);}}
function selectProduct(){selected=$('portfolio-product').value;if(!selected)return;$('portfolio-detail').href='/features/my-product/'+selected;
 if(!chart)chart=new PerformanceChart(selected,()=>{},failure);else{chart.id=selected;chart.samples=[];chart.zoom=1;chart.follow=true;}
 chart.refresh();history();summaries();
}
$('portfolio-product').addEventListener('change',selectProduct);
document.addEventListener('portfolio-updated',event=>{if(!lastSnapshot||Date.now()-lastSnapshot>30000){snapshot();}else{event.detail.items.forEach(p=>purchases.set(p.id,p));summaries();}if(selected)history();});
$('my-refresh').addEventListener('click',()=>{lastSnapshot=0;});
setInterval(()=>{if(chart&&!document.hidden&&!$('portfolio-analytics').hidden)chart.refresh();},5000);
function tabs(){const hash=['#products','#activity'].includes(location.hash)?location.hash:'#overview';document.querySelectorAll('.portfolio-tabs a').forEach(a=>{if(a.hash===hash)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');});if(location.hash)requestAnimationFrame(()=>document.querySelector(hash)?.scrollIntoView({block:'start'}));}
window.addEventListener('hashchange',tabs);tabs();

snapshot();
