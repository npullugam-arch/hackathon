import {request} from './api.js';
import {element} from './catalog-ui.js';
import {money,date,badge,button,purchaseDetails,claimsRows} from './commerce-ui.js';
const $=id=>document.getElementById(id);let loading=false,filters=new URLSearchParams(),version=0;
const state={sales:{page:0,pages:0},records:{page:0,pages:0}};
function controls(){$('purchases-refresh').disabled=loading;for(const [key,s]of Object.entries(state)){$(key+'-previous').disabled=loading||s.page===0;$(key+'-next').disabled=loading||s.page+1>=s.pages;}}
function failure(ex,id='purchases-error'){$(id).textContent=ex.message;$(id).hidden=false;if(ex.status===401)location.replace('/admin');}
async function open(id){const v=++version;$('sold-details').replaceChildren();$('sold-claims').replaceChildren();$('sold-error').hidden=true;$('sold-detail-dialog').showModal();try{const data=await request('/api/admin/purchases/'+id);if(v!==version)return;purchaseDetails($('sold-details'),data.purchase);claimsRows($('sold-claims'),data.claims);}catch(ex){if(v===version)failure(ex,'sold-error');}}
async function load(){if(loading)return;loading=true;controls();$('purchases-error').hidden=true;
  try{const results=await Promise.all(Object.entries(state).map(async([key,s])=>{const q=new URLSearchParams(filters);q.set('page',s.page);q.set('size',20);return [key,await request((key==='sales'?'/api/admin/product-sales?':'/api/admin/purchases?')+q)];}));
    for(const [key,data]of results){state[key]={page:data.page,pages:data.totalPages};$(key+'-page').textContent=`${data.total} records | Page ${data.totalPages?data.page+1:0} of ${data.totalPages}`;
      const rows=$(key==='sales'?'admin-purchases-rows':'purchase-records');rows.replaceChildren();$(key==='sales'?'purchases-empty':'purchase-records-empty').hidden=data.items.length>0;
      for(const item of data.items){const row=element('tr');
        if(key==='sales'){const product=element('td','',item.productTitle);product.append(element('small','',item.productId));row.append(product);for(const value of [item.soldCount+' / '+item.purchaserCount,money(item.salesPaise),item.activePurchases,item.completedPurchases,money(item.claimedPaise),money(item.unclaimedPaise)])row.append(element('td','',value));}
        else{const product=element('td');product.append(button(item.productTitle,()=>open(item.id)),element('small','',item.id));const user=element('td','',item.userName||item.userId);user.append(element('small','',item.userId));row.append(product,user,element('td','',money(item.purchaseAmountPaise)),element('td','',date(item.purchaseDate)));for(const value of [item.paymentStatus,item.status]){const cell=element('td');cell.append(badge(value));row.append(cell);}row.append(element('td','',money(item.claimedIncomePaise)+' / '+money(item.totalExpectedPaise)),element('td','',item.progressPercent+'%'));}
        rows.append(row);
      }
    }
  }catch(ex){failure(ex);}finally{loading=false;controls();}}
$('purchases-filters').addEventListener('submit',event=>{event.preventDefault();if(loading)return;filters=new URLSearchParams();for(const [id,key]of [['purchases-product','productId'],['purchases-user','user'],['purchases-status','status'],['purchases-payment','paymentStatus'],['purchases-sort','sort'],['purchases-from','from'],['purchases-to','to']])if($(id).value.trim())filters.set(key,$(id).value.trim());state.sales.page=state.records.page=0;load();});
for(const key of Object.keys(state)){ $(key+'-previous').addEventListener('click',()=>{state[key].page--;load();});$(key+'-next').addEventListener('click',()=>{state[key].page++;load();});}
$('purchases-refresh').addEventListener('click',load);$('sold-close').addEventListener('click',()=>$('sold-detail-dialog').close());$('sold-detail-dialog').addEventListener('close',()=>version++);document.addEventListener('admin-section-change',event=>{if(event.detail==='purchases-section')load();});
