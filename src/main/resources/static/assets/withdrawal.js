import {watchWithdrawals} from './withdrawal-live.js';
import {request,post,showMessage} from './api.js';
import {confirmAction} from './confirm-modal.js';
import {money,element,detail,historyRow} from './withdrawal-ui.js';
const $=id=>document.getElementById(id);
let dashboard, directory=[], page=0, pages=0, busy=false, loading=false, pending=null, storageKey, draft, cooldownTimer;
const form=$('withdraw-form'), bankForm=$('bank-form');
function error(id,text=''){ $(id).textContent=text; $(id).hidden=!text; }
function failure(ex){ if(ex.status===401) location.replace('/login'); else showMessage(ex.message); }
function controls(){
  const weekend=withdrawalWeekend(); $('withdraw-weekend').hidden=!weekend;
  $('withdraw-fields').disabled=busy || !dashboard || !dashboard.bankAccounts.length || !!pending || withdrawalCooldown() || weekend;
  $('add-bank').disabled=busy || !dashboard?.bankSetupConfigured;
  $('withdraw-refresh').disabled=busy || loading;
  $('withdraw-previous').disabled=loading || busy || page===0;
  $('withdraw-next').disabled=loading || busy || page+1>=pages;
  $('pending-withdrawal').hidden=!pending;
  $('retry-withdrawal').disabled=busy || !dashboard;
  $('confirm-withdraw').disabled=busy; $('cancel-withdraw').disabled=busy;
  $('bank-fields').disabled=busy;
  for(const button of document.querySelectorAll('#bank-cards button'))button.disabled=busy || !!pending;
}
function readPending(){
  try { const value=JSON.parse(localStorage.getItem(storageKey)); pending=value?.idempotencyKey && value?.bankAccountId && value?.amount ? value : null; }
  catch { pending=null; }
}
function remaining(){ const paise=parseAmount(); $('withdraw-remaining').textContent='Remaining Winning Cash: '+(paise===null || !dashboard ? '—' : money(dashboard.availableWinningPaise-paise)); }
function parseAmount(){ const value=$('withdraw-amount').value; if(!/^\d+(\.\d{1,2})?$/.test(value))return null; const [rupees,fraction='']=value.split('.'); const result=Number(rupees)*100+Number(fraction.padEnd(2,'0')); return Number.isSafeInteger(result) ? result : null; }
function renderBanks(){
  const selected=$('withdraw-bank').value; $('withdraw-bank').replaceChildren(new Option('Select a bank account','')); $('bank-cards').replaceChildren();
  for(const bank of dashboard.bankAccounts){
    $('withdraw-bank').append(new Option(bank.nickname+' · '+bank.bankName+' '+bank.maskedAccountNumber,bank.id));
    const card=element('article','','bank-card'); card.append(element('strong',bank.nickname),element('p',bank.bankName+' · '+bank.maskedAccountNumber),element('p',bank.holderName),element('small',bank.ifsc));
    const select=element('button','Use this account','button secondary'); select.type='button';select.disabled=!!pending;
    select.addEventListener('click',()=>{$('withdraw-bank').value=bank.id; $('withdraw-amount').focus();});
    const remove=element('button','Deactivate','button secondary');remove.type='button';remove.disabled=busy;
    remove.addEventListener('click',async()=>{if(busy || !await confirmAction('Deactivate '+bank.nickname+' '+bank.maskedAccountNumber+'? Its withdrawal history will be retained.','Deactivate bank account'))return;busy=true;controls();try{const csrf=await request('/api/auth/csrf');await request('/api/withdrawals/bank-accounts/'+bank.id,{method:'DELETE',headers:{[csrf.headerName]:csrf.token}});await load();showMessage('Bank account deactivated.','success');}catch(ex){failure(ex);}finally{busy=false;controls();}});
    card.append(element('div'));card.lastChild.append(select,remove);$('bank-cards').append(card);
  }
  if(dashboard.bankAccounts.some(b=>b.id===selected))$('withdraw-bank').value=selected;
  else if(dashboard.bankAccounts.length===1)$('withdraw-bank').value=dashboard.bankAccounts[0].id;
  $('banks-empty').hidden=dashboard.bankAccounts.length>0;$('banks-empty').textContent='No saved bank accounts. Add a bank account to get started.';
}
async function load(){
  if(loading)return;loading=true;controls();
  try{
    const {dashboard:data,history}=await request('/api/withdrawals/snapshot?page='+page);
    const banksChanged=JSON.stringify(dashboard?.bankAccounts)!==JSON.stringify(data.bankAccounts);
    dashboard=data;$('withdraw-amount').min=String(data.minimumAmountPaise/100);$('withdraw-minimum').textContent='Minimum '+money(data.minimumAmountPaise)+'. Amounts may include up to two decimal places.';storageKey='withdrawal-pending:'+data.userId;readPending();
    $('withdraw-recharge').textContent=money(data.availableRechargePaise);$('withdraw-earned').textContent=money(data.currentWinningPaise);$('withdraw-available').textContent=money(data.availableWinningPaise);$('withdraw-reserved').textContent=money(data.reservedPaise)+' reserved in processing requests';
    $('bank-config').hidden=data.bankSetupConfigured;if(banksChanged)renderBanks();remaining();
    pages=history.totalPages;page=history.page;$('withdraw-history').replaceChildren(...history.items.map(historyRow));$('withdraw-history-empty').hidden=history.items.length>0;$('withdraw-history-empty').textContent='No withdrawals yet. Your requests will appear here.';$('withdraw-page').textContent=pages ? `Page ${page+1} of ${pages}` : 'No requests';
  }catch(ex){failure(ex);}finally{loading=false;$('withdraw-loading').hidden=true;renderCooldown();controls();}
}
function bankOptions(){
  const selected=$('bank-code').value;
  $('bank-code').replaceChildren(new Option('Select bank',''),...directory.map(b=>new Option(b.name,b.code)));
  $('bank-code').value=selected;
}
function withdrawalWeekend(){const day=new Intl.DateTimeFormat('en-US',{timeZone:'Asia/Kolkata',weekday:'short'}).format(new Date());return day==='Sat'||day==='Sun';}
function withdrawalCooldown(){return !!dashboard?.nextWithdrawalAt && Date.parse(dashboard.nextWithdrawalAt)>Date.now();}
function renderCooldown(){const box=$('withdraw-cooldown');if(!box)return;const next=dashboard?.nextWithdrawalAt?Date.parse(dashboard.nextWithdrawalAt):NaN;const active=Number.isFinite(next)&&next>Date.now();box.hidden=!active;if(active){let seconds=Math.max(0,Math.ceil((next-Date.now())/1000));const hours=Math.floor(seconds/3600);seconds%=3600;const minutes=Math.floor(seconds/60);const secs=seconds%60;$('withdraw-countdown').textContent=`${hours}h ${String(minutes).padStart(2,'0')}m ${String(secs).padStart(2,'0')}s`;$('withdraw-next-at').textContent='Available '+new Date(next).toLocaleString(undefined,{dateStyle:'medium',timeStyle:'short'});}}
$('add-bank').addEventListener('click',async()=>{
  bankForm.reset();error('bank-error');$('bank-dialog').showModal();
  try{if(!directory.length)directory=await request('/api/withdrawals/banks');bankOptions();}catch(ex){error('bank-error',ex.message);}
});
$('bank-ifsc').addEventListener('input',()=>{$('bank-ifsc').value=$('bank-ifsc').value.toUpperCase();});
for(const button of document.querySelectorAll('[data-close]'))button.addEventListener('click',()=>{if(!busy)$(button.dataset.close).close();});
$('bank-dialog').addEventListener('close',()=>bankForm.reset());
$('bank-dialog').addEventListener('cancel',event=>{if(busy)event.preventDefault();});
bankForm.addEventListener('submit',async event=>{
  event.preventDefault();if(busy)return;const data=Object.fromEntries(new FormData(bankForm));
  for(const field of ['holderName','nickname'])data[field]=data[field].trim();
  if(data.accountNumber!==data.confirmAccountNumber)return error('bank-error','The account numbers do not match.');
  if(!data.ifsc.startsWith(data.bankCode))return error('bank-error','The IFSC must belong to the selected bank.');
  if(!data.holderName || !data.nickname || /^0+$/.test(data.accountNumber))return error('bank-error','Enter valid account details and a nickname.');
  busy=true;controls();error('bank-error');
  try{await post('/api/withdrawals/bank-accounts',data);$('bank-dialog').close();await load();showMessage('Bank account saved.','success');}catch(ex){error('bank-error',ex.message);}finally{busy=false;controls();}
});
form.addEventListener('submit',event=>{
  event.preventDefault();if(busy || pending || !dashboard)return;if(withdrawalWeekend()){controls();return showMessage('Withdrawals are unavailable on Saturday and Sunday. They will be available again Monday at 12:00 AM.');}if(withdrawalCooldown()){renderCooldown();return showMessage('Your withdrawal cooldown is still active. Please try again when the timer ends.');}const amount=parseAmount();const bank=dashboard.bankAccounts.find(b=>b.id===$('withdraw-bank').value);
  if(!bank || amount===null || amount<dashboard.minimumAmountPaise || amount>dashboard.availableWinningPaise)return showMessage('Select a bank and enter at least '+money(dashboard.minimumAmountPaise)+', within your available Winning Cash.');
  draft={bankAccountId:bank.id,amount:$('withdraw-amount').value,idempotencyKey:crypto.randomUUID()};
  $('withdraw-confirm-details').replaceChildren();for(const [label,value] of [['Withdrawal amount',money(amount)],['Bank',bank.bankName],['Account',bank.maskedAccountNumber],['Account holder',bank.holderName],['Remaining Winning Cash',money(dashboard.availableWinningPaise-amount)],['Expected status','Processing']])detail($('withdraw-confirm-details'),label,value);
  error('confirm-error');$('withdraw-confirm').showModal();
});
async function submit(value){
  if(busy)return;busy=true;controls();error('confirm-error');
  try{
    // Persist before sending; a lost response or page refresh must reuse this request ID.
    localStorage.setItem(storageKey,JSON.stringify(value));pending=value;
    const result=await post('/api/withdrawals',value);
    localStorage.removeItem(storageKey);pending=null;draft=null;$('withdraw-confirm').close();form.reset();renderBanks();page=0;
    showMessage('Withdrawal '+result.id+' is '+result.status.toLowerCase()+'.','success');await load();
  }catch(ex){
    if([400,404,409].includes(ex.status)){localStorage.removeItem(storageKey);pending=null;}
    error('confirm-error',ex.message);failure(ex);
  }finally{busy=false;controls();}
}
$('confirm-withdraw').addEventListener('click',()=>{if(draft)submit(draft);});
$('cancel-withdraw').addEventListener('click',()=>{if(!busy)$('withdraw-confirm').close();});
$('withdraw-confirm').addEventListener('cancel',event=>{if(busy)event.preventDefault();});
$('retry-withdrawal').addEventListener('click',()=>{if(pending)submit(pending);});
$('withdraw-amount').addEventListener('input',remaining);$('withdraw-refresh').addEventListener('click',load);
$('withdraw-previous').addEventListener('click',()=>{if(page>0){page--;load();}});$('withdraw-next').addEventListener('click',()=>{if(page+1<pages){page++;load();}});
window.addEventListener('pageshow',event=>{if(event.persisted)load();});window.addEventListener('storage',event=>{if(event.key===storageKey){readPending();controls();}});
document.addEventListener('visibilitychange',()=>{if(!document.hidden && !busy)load();});
setInterval(()=>{if(!document.hidden && !busy && !$('bank-dialog').open && !$('withdraw-confirm').open)load();},30000);cooldownTimer=setInterval(renderCooldown,1000);
load();

watchWithdrawals(load, () => busy || loading);
