import {confirmAction} from './confirm-modal.js';
import {request} from './api.js';
import {adminWrite} from './admin-api.js';
import {money,date,element,detail,badge} from './withdrawal-ui.js';
const $=id=>document.getElementById(id), section=$('withdrawals-section'), dialog=$('admin-withdrawal-dialog');
let filters=new URLSearchParams(),page=0,pages=0,loading=false,busy=false,current,detailVersion=0,revealTimer;
function error(id,text=''){$(id).textContent=text;$(id).hidden=!text;}
function failure(ex,id='admin-withdraw-error'){if(ex.status===401)location.replace('/admin');else error(id,ex.message);}
function controls(){$('admin-withdraw-previous').disabled=loading || page===0;$('admin-withdraw-next').disabled=loading || page+1>=pages;$('admin-withdraw-refresh').disabled=loading;$('aw-action-fields').disabled=busy;$('aw-reveal').disabled=busy;$('aw-close').disabled=busy;}
function hideSensitive(){clearTimeout(revealTimer);$('aw-sensitive').textContent='';$('aw-sensitive').hidden=true;}
async function load(){
  if(loading)return;loading=true;controls();error('admin-withdraw-error');$('admin-withdraw-loading').hidden=false;$('admin-withdraw-loading').textContent='Loading withdrawals…';
  try{
    const query=new URLSearchParams(filters);query.set('page',page);const data=await request('/api/admin/withdrawals?'+query);
    page=data.page;pages=data.totalPages;$('admin-withdraw-totals').replaceChildren(...[['Processing',data.summary.processing],['Successful',data.summary.completed],['Failed / refunded',data.summary.refunded],['Total requested',money(data.summary.totalRequestedPaise)]].map(([label,value])=>{const card=element('article');card.append(element('p',label),element('strong',String(value)));return card;}));$('admin-withdraw-rows').replaceChildren();
    for(const item of data.items){
      const row=element('tr'), id=element('td'), button=element('button',item.id,'button secondary withdraw-ref');button.type='button';button.addEventListener('click',()=>open(item.id));id.append(button);
      const user=element('td',item.userName || item.email || item.userId);user.append(element('small',item.userId),element('small',item.email || ''),element('small',item.phone || ''));
      const bank=element('td',item.bankAccount.bankName);bank.append(element('small',item.accountNumber),element('small',item.bankAccount.holderName),element('small',item.bankAccount.ifsc),element('small',item.bankAccount.nickname));
      const status=element('td');status.append(badge(item));const reference=element('td',item.referenceId || '—');reference.append(element('small',item.adminRemark || ''),element('small',item.failureReason || ''));
      row.append(id,user,element('td',money(item.amountPaise)),bank,element('td',date(item.requestedAt)),status,reference,element('td',date(item.completedAt || item.rejectedAt || item.processedAt)));$('admin-withdraw-rows').append(row);
    }
    $('admin-withdraw-loading').hidden=data.items.length>0;$('admin-withdraw-loading').textContent='No withdrawals match these filters.';$('admin-withdraw-page').textContent=`${data.total} requests · Page ${pages ? page+1 : 0} of ${pages}`;
  }catch(ex){failure(ex);$('admin-withdraw-loading').hidden=true;}finally{loading=false;controls();}
}
function renderDetails(item){
  current=item;hideSensitive();$('aw-details').replaceChildren();
  for(const [label,value] of [['Withdrawal ID',item.id],['User',item.userName || item.email],['User ID',item.userId],['Email',item.email],['Phone',item.phone],['Amount',money(item.amountPaise)],['Bank',item.bankAccount.bankName],['Account',item.accountNumber],['Holder',item.bankAccount.holderName],['IFSC',item.bankAccount.ifsc],['Nickname',item.bankAccount.nickname],['Status',item.status],['Failure type',item.failureKind],['Reference',item.referenceId],['Admin remark',item.adminRemark],['Failure reason',item.failureReason],['Requested',date(item.requestedAt)],['Processed',date(item.processedAt)],['Successful',date(item.completedAt)],['Rejected / refunded',date(item.rejectedAt)]])detail($('aw-details'),label,value);
  $('aw-processing').hidden=item.status!=='PROCESSING';$('aw-action-form').reset();$('aw-remark').value=item.adminRemark || '';actionFields();
}
async function open(id){
  const version=++detailVersion;$('aw-notice').hidden=true;hideSensitive();current=null;$('aw-details').replaceChildren();$('aw-processing').hidden=true;error('aw-error','Loading withdrawal…');if(!dialog.open)dialog.showModal();
  try{const item=await request('/api/admin/withdrawals/'+id);if(version!==detailVersion || !dialog.open)return;error('aw-error');renderDetails(item);}catch(ex){if(version===detailVersion)failure(ex,'aw-error');}
}
function actionFields(){const action=$('aw-action').value;$('aw-reference').required=action==='SUCCESSFUL';$('aw-reference').disabled=action!=='SUCCESSFUL';$('aw-reason').required=['FAILED','ERROR','REJECTED','REFUNDED','CANCELLED'].includes(action);$('aw-reason').disabled=!$('aw-reason').required;$('aw-acknowledge').checked=false;}
$('aw-action').addEventListener('change',actionFields);
$('aw-action-form').addEventListener('submit',async event=>{
  event.preventDefault();if(busy || !current)return;
  const data=Object.fromEntries(new FormData(event.currentTarget));busy=true;controls();error('aw-error');
  const refund=['FAILED','ERROR','REJECTED','REFUNDED','CANCELLED'].includes(data.status);
  if(!await confirmAction(refund ? `Refund exactly ${money(current.amountPaise)} to this user's Available Winning Cash? Confirm that the bank transfer did not succeed.` : `Mark this withdrawal ${data.status.toLowerCase()}? This will not deduct Winning Cash again.`, 'Confirm withdrawal outcome')){busy=false;controls();return;}
  try{const item=await adminWrite('/api/admin/withdrawals/'+current.id+'/status',data);renderDetails({...item,accountNumber:current.accountNumber});$('aw-notice').textContent=refund ? 'Withdrawal refunded. The exact requested amount has been returned to Available Winning Cash.' : 'Withdrawal status saved successfully.';$('aw-notice').hidden=false;await load();}
  catch(ex){failure(ex,'aw-error');}finally{busy=false;controls();}
});
$('aw-reveal').addEventListener('click',async()=>{
  if(busy || !current)return;busy=true;controls();hideSensitive();error('aw-error');
  try{const data=await adminWrite('/api/admin/withdrawals/'+current.id+'/payout-details');$('aw-sensitive').textContent='Account number: '+data.accountNumber;$('aw-sensitive').hidden=false;revealTimer=setTimeout(hideSensitive,60000);}
  catch(ex){failure(ex,'aw-error');}finally{busy=false;controls();}
});
$('aw-close').addEventListener('click',()=>{if(!busy)dialog.close();});dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});dialog.addEventListener('close',()=>{detailVersion++;hideSensitive();current=null;});
$('admin-withdraw-filters').addEventListener('submit',event=>{event.preventDefault();if(loading)return;filters=new URLSearchParams();for(const [key,value] of new FormData(event.currentTarget))if(value.trim())filters.set(key,value.trim());page=0;load();});
$('admin-withdraw-filters').addEventListener('reset',()=>{filters=new URLSearchParams();page=0;load();});
$('admin-withdraw-refresh').addEventListener('click',load);$('admin-withdraw-previous').addEventListener('click',()=>{if(page>0){page--;load();}});$('admin-withdraw-next').addEventListener('click',()=>{if(page+1<pages){page++;load();}});
document.addEventListener('admin-section-change',event=>{if(event.detail==='withdrawals-section')load();else hideSensitive();});
document.addEventListener('visibilitychange',()=>{hideSensitive();if(!document.hidden && !section.hidden && !busy)load();});
setInterval(()=>{if(!document.hidden && !section.hidden && !busy && !dialog.open)load();},15000);
