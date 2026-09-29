import {request,showMessage} from './api.js';
import {element} from './catalog-ui.js';
import {watchWithdrawals} from './withdrawal-live.js';
const $=id=>document.getElementById(id),money=n=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2}).format(n/100);
let state,busy=false,loading=false,requestId=null,file=null,revision='',seen=new Map();
function controls(){for(const button of document.querySelectorAll('#photo-history button'))button.disabled=busy||loading;$('photo-fields').disabled=busy||!state?.eligible;$('photo-submit').textContent=busy?'Uploading securely...':'Submit for verification';$('photo-refresh').disabled=busy||loading;}
function fail(error){if(error.status===401)return location.replace('/login');showMessage(error.message);}
function render(value){state=value;$('photo-machine-title').textContent=value.machineName;$('photo-balance').textContent=money(value.availableWinningPaise);
 const latest=value.tasks[0],availability=$('photo-availability');if(!value.purchased){const locked=element('span','photo-status locked','LOCKED');availability.replaceChildren(locked,document.createTextNode(' Purchase any product to unlock this task.'));}else availability.textContent=!value.machineActive?'This machine is currently inactive. Your submitted task and reward history remain available.':value.eligible?'Ready to submit.':latest?.status==='COMPLETED'?(latest.ledgerId||value.rewards.some(r=>r.sourceId===latest.id&&r.status==='CLAIMED')?'CLAIMED: \u20b920 credited to your available withdrawable balance.':'COMPLETED: Your photo was approved. Claim your reward below.'):latest?.status==='PENDING'?'Your photo is awaiting verification.':'Ready to submit.';
 const key=JSON.stringify([value.tasks,value.rewards]);if(key!==revision){revision=key;$('photo-history').replaceChildren();for(const task of value.tasks){
 const reward=value.rewards.find(item=>item.type==='TAKE_PHOTO'&&item.sourceId===task.id),status=task.status==='COMPLETED'?(reward?.status==='CLAIMED'||task.ledgerId?'CLAIMED':'COMPLETED'):task.status;
 const card=element('article','photo-submission'),top=element('div','card-top');top.append(element('h3','','Take Photo'),element('span',`photo-status ${status.toLowerCase()}`,status));card.append(top);
 const img=element('img','photo-history-image');img.alt='Submitted photo';img.loading='lazy';img.referrerPolicy='no-referrer';img.src=`/api/photo-tasks/${task.id}/image`;img.addEventListener('error',()=>img.replaceWith(element('p','muted','Preview unavailable. Refresh to retry.')));card.append(img);
 card.append(element('p','muted',`Submitted ${new Date(task.submittedAt).toLocaleString()}`),element('p','photo-receipt',task.status==='COMPLETED'?(status==='CLAIMED'?'Reward claimed: \u20b920 added to withdrawable balance':'Approved: \u20b920 ready to claim'):task.status==='REJECTED'?`Rejected: ${task.rejectionReason}`:'Reward: \u20b920 after approval'));
 if(reward?.status==='COMPLETED'){const claim=element('button','button primary','Claim \u20b920');claim.type='button';claim.disabled=busy;claim.addEventListener('click',()=>claimReward(reward.id));card.append(claim);}
 if(task.reviewedAt)card.append(element('p','muted',`Reviewed ${new Date(task.reviewedAt).toLocaleString()}`));
 if(reward?.ledgerId||task.ledgerId)card.append(element('p','photo-reference',`Reward reference: ${reward?.ledgerId||task.ledgerId}`));$('photo-history').append(card);
 if(seen.get(task.id)==='PENDING'&&task.status==='COMPLETED')showMessage('Task approved. Claim your \u20b920 reward.','success');
 if(seen.get(task.id)==='PENDING'&&task.status==='REJECTED')showMessage(`Photo rejected: ${task.rejectionReason}`);seen.set(task.id,task.status);
 }}$('photo-empty').hidden=value.tasks.length>0;$('photo-workspace').hidden=false;controls();}
async function load(){if(loading||busy)return;loading=true;controls();try{render(await request('/api/photo-tasks'));$('photo-retry').hidden=true;}catch(error){fail(error);$('photo-retry').hidden=false;}finally{loading=false;$('photo-loading').hidden=true;controls();}}
async function claimReward(id){if(busy)return;busy=true;controls();try{const csrf=await request('/api/auth/csrf');const result=await request(`/api/machine-rewards/${id}/claim`,{method:'POST',headers:{[csrf.headerName]:csrf.token}});$('photo-balance').textContent=money(result.availableWinningPaise);showMessage('\u20b920 has been added to your available withdrawable balance.','success');revision='';}catch(error){fail(error);}finally{busy=false;await load();}}
$('task-photo').addEventListener('change',()=>{file=$('task-photo').files[0];requestId=crypto.randomUUID();$('photo-preview').hidden=true;$('photo-preview').removeAttribute('src');$('photo-file-error').hidden=true;
 if(!file)return;if(!['image/jpeg','image/png'].includes(file.type)||file.size>5*1024*1024){$('photo-file-error').textContent='Choose a JPEG or PNG image up to 5 MB.';$('photo-file-error').hidden=false;file=null;$('task-photo').value='';return;}
 const selected=file,reader=new FileReader();reader.onload=()=>{if(file!==selected)return;$('photo-preview').src=reader.result;$('photo-preview').hidden=false;};reader.onerror=()=>{if(file===selected){file=null;$('task-photo').value='';showMessage('The selected photo could not be read. Please choose it again.');}};reader.readAsDataURL(file);
});
$('photo-form').addEventListener('submit',async event=>{event.preventDefault();if(busy||!state?.eligible||!file)return;busy=true;controls();try{
 const csrf=await request('/api/auth/csrf'),body=new FormData();body.append('requestId',requestId);body.append('photo',file);
 await request('/api/photo-tasks',{method:'POST',headers:{[csrf.headerName]:csrf.token},body});$('photo-form').reset();file=null;requestId=null;$('photo-preview').hidden=true;$('photo-preview').removeAttribute('src');showMessage('Photo submitted. Your task is pending verification.','success');
 }catch(error){fail(error);}finally{busy=false;controls();await load();}});
$('photo-refresh').addEventListener('click',()=>{revision='';load();});$('photo-retry').addEventListener('click',load);load();watchWithdrawals(load,()=>busy);
