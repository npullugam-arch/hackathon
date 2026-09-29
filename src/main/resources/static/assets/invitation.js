import {watchWithdrawals} from './withdrawal-live.js';
import {request,post} from './api.js';
import {confirmAction} from './confirm-modal.js';
import {money,invitationRows} from './commerce-ui.js';
const $=id=>document.getElementById(id);let page=0,pages=0,loading=false,busy=false,data;
function controls(){$('share-link').disabled=!data?.eligibleForReferAndEarn||busy;for(const button of document.querySelectorAll('#referral-rewards button'))button.disabled=busy||loading;$('copy-link').disabled=!data?.eligibleForReferAndEarn||busy;$('invite-previous').disabled=loading||page===0;$('invite-next').disabled=loading||page+1>=pages;$('invite-refresh').disabled=loading||busy;$('bind-button').disabled=loading||busy||!data?.canBind;$('copy-code').disabled=!data?.eligibleForReferAndEarn;$('inviter-code').disabled=busy||!data?.canBind;}
function toast(text,kind='success'){
  const node=$('invitation-toast');clearTimeout(toast.timer);clearTimeout(toast.hideTimer);
  node.setAttribute('role',kind==='error'?'alert':'status');node.textContent=text;node.className=`invitation-toast ${kind}`;node.hidden=false;
  requestAnimationFrame(()=>node.classList.add('visible'));
  toast.timer=setTimeout(()=>{node.classList.remove('visible');toast.hideTimer=setTimeout(()=>{node.hidden=true;},220);},5000);
}
function failure(ex){if(ex.status===401)location.replace('/login');else toast(ex.message,'error');}
function renderInviter(inviter){
  const card=$('inviter-card');card.replaceChildren();card.hidden=!inviter;if(!inviter)return;
  const name=inviter.name?.trim()||'Launchpad member';let photo;
  try{const url=new URL(inviter.photoUrl);if(url.protocol==='https:')photo=url.href;}catch{}
  const fallback=()=>{const node=document.createElement('div');node.className='inviter-placeholder';node.setAttribute('aria-hidden','true');node.textContent=name.charAt(0).toUpperCase();return node;};
  let visual=fallback();if(photo){visual=document.createElement('img');visual.className='inviter-photo';visual.alt=name+' profile photo';visual.referrerPolicy='no-referrer';visual.addEventListener('error',()=>visual.replaceWith(fallback()),{once:true});visual.src=photo;}
  const copy=document.createElement('div');copy.className='inviter-copy';const label=document.createElement('p');label.textContent='You were invited by';
  const heading=document.createElement('h3');heading.textContent=name;const id=document.createElement('p');id.className='inviter-id';id.textContent=`ID: ${inviter.userId}`;
  copy.append(label,heading,id);card.append(visual,copy);
}
function updateBindingUI(bound){$('bind-invitation').hidden=bound;$('bound-code').hidden=bound;}
function renderDashboard(result){
  data=result;const url=new URL('/register',location.origin);url.searchParams.set('ref',data.code);$('referral-link').value=data.eligibleForReferAndEarn?url.href:'';const history=data.invitations;pages=history.totalPages;page=history.page;
  $('invitation-code').textContent=data.eligibleForReferAndEarn?data.code:'Purchase a product to unlock your code';$('invite-total').textContent=data.totalInvited;$('invite-success').textContent=data.successfulInvitations;$('invite-rewards').textContent=money(data.totalRewardsPaise);
  renderInviter(data.inviter);updateBindingUI(Boolean(data.boundCode));
  $('bound-code').textContent=data.canBind?'Enter a code before starting your first purchase.':'Invitation binding is closed because you have already started a purchase.';
  const eligibility=$('referral-eligibility');if(data.eligibleForReferAndEarn)eligibility.textContent='Refer & Earn is unlocked. Your ₹30 reward appears here after your friend completes a qualifying purchase.';else eligibility.replaceChildren(Object.assign(document.createElement('span'),{className:'photo-status locked',textContent:'LOCKED'}),document.createTextNode(' Purchase any product to unlock Refer & Earn.'));
  renderRewards(data.machineRewards);
  invitationRows($('invite-rows'),history.items);$('invite-empty').hidden=history.items.length>0;$('invite-empty').textContent='No invitations yet. Share your code to get started.';$('invite-page').textContent=`Page ${pages?page+1:0} of ${pages}`;
}
function renderRewards(rewards){const target=$('referral-rewards');target.replaceChildren();target.append(Object.assign(document.createElement('p'),{className:'muted',textContent:`Available withdrawable balance: ${money(data.availableWinningPaise)}`}));const items=rewards.filter(reward=>reward.type==='REFERRAL');if(!items.length){target.append(Object.assign(document.createElement('p'),{className:'muted',textContent:data.eligibleForReferAndEarn?'PENDING: Invite a friend. Your reward becomes COMPLETED after their first qualifying purchase.':'LOCKED: Purchase any product to unlock Refer & Earn.'}));return;}for(const reward of items){const row=document.createElement('article');row.className='referral-reward';const status=reward.status==='CLAIMED'?'CLAIMED':'COMPLETED';row.append(Object.assign(document.createElement('strong'),{textContent:'₹30 Refer & Earn'}),Object.assign(document.createElement('span'),{className:`referral-status ${status.toLowerCase()}`,textContent:status}));if(reward.status==='COMPLETED'){const claim=document.createElement('button');claim.type='button';claim.className='button primary';claim.textContent='Claim ₹30';claim.disabled=busy;claim.addEventListener('click',()=>claimReward(reward.id));row.append(claim);}target.append(row);}}
async function claimReward(id){if(busy)return;busy=true;controls();try{const result=await post(`/api/machine-rewards/${id}/claim`,{});toast(`₹30 added. Available withdrawable balance: ${money(result.availableWinningPaise)}.`);busy=false;await load();}catch(ex){failure(ex);}finally{busy=false;controls();}}
async function load(){if(loading||busy)return;loading=true;controls();try{renderDashboard(await request('/api/invitations?page='+page));}catch(ex){failure(ex);}finally{loading=false;controls();}}
$('bind-invitation').addEventListener('submit',async event=>{
  event.preventDefault();if(busy||loading||!data?.canBind)return;busy=true;controls();
  try{
    if(!await confirmAction('Permanently bind this invitation code? It cannot be changed.','Bind invitation'))return;
    const result=await post('/api/invitations/bind',{code:$('inviter-code').value.trim().toUpperCase()});
    renderDashboard(result);toast('Invitation code saved successfully.');
  }catch(ex){failure(ex);}finally{busy=false;controls();}
});
$('copy-code').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(data.code);toast('Invitation code copied.');}catch{toast('Copy is unavailable in this browser. Select and copy the code shown above.','error');}});
$('invite-refresh').addEventListener('click',load);$('invite-previous').addEventListener('click',()=>{page--;load();});$('invite-next').addEventListener('click',()=>{page++;load();});load();watchWithdrawals(load,()=>busy);

$('copy-link').addEventListener('click',async()=>{if(!data?.eligibleForReferAndEarn)return;const link=new URL('/register',location.origin);link.searchParams.set('ref',data.code);try{await navigator.clipboard.writeText(link.href);toast('Invitation link copied.');}catch{toast('Copy is unavailable. Share your invitation code instead.','error');}});
const referredCode=new URLSearchParams(location.search).get('ref');if(referredCode&&/^[a-f0-9]{32}$/i.test(referredCode)){$('inviter-code').value=referredCode.toUpperCase();toast('Your invitation code is ready. Bind it before your first purchase.');}

$('share-link').addEventListener('click',async()=>{if(!data?.eligibleForReferAndEarn)return;try{const url=$('referral-link').value;if(navigator.share)await navigator.share({title:'Launchpad invitation',url});else{await navigator.clipboard.writeText(url);toast('Invitation link copied.');}}catch(error){if(error.name!=='AbortError')toast('Sharing is unavailable. Copy the link shown above.','error');}});
