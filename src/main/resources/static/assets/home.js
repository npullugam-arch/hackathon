import {request,showMessage} from './api.js';
import './machines.js';
import {currentUser} from './session-user.js';
import {money} from './commerce-ui.js';
async function profile(){try{const user=await currentUser();const name=user.name||user.email?.split('@')[0]||'there';
document.getElementById('home-name').textContent=`Welcome, ${name}`;
document.getElementById('home-email').textContent=user.email||'Your personal workspace';
const initial=document.getElementById('home-initial'),avatar=document.getElementById('home-avatar');initial.textContent=name.slice(0,1).toUpperCase();
if(user.photoUrl){try{const url=new URL(user.photoUrl);if(url.protocol==='https:'){avatar.onload=()=>{avatar.hidden=false;initial.hidden=true;};avatar.onerror=()=>{avatar.hidden=true;initial.hidden=false;};avatar.src=url.href;}}catch{}}
}catch(e){if(e.status===401)location.replace('/login');else{document.getElementById('home-email').textContent='Profile temporarily unavailable';showMessage(e.message);}}}
profile();document.addEventListener('visibilitychange',()=>{if(!document.hidden)profile();});

async function wallet(){
 const error=document.getElementById('home-wallet-error');
 const results=await Promise.allSettled([request('/api/wallet'),request('/api/withdrawals/dashboard')]);
 document.getElementById('home-wallet-balance').textContent=results[0].status==='fulfilled'?money(results[0].value.balancePaise):'Unavailable';
 document.getElementById('home-withdrawable').textContent=results[1].status==='fulfilled'?money(results[1].value.availableWinningPaise):'Unavailable';
 error.hidden=results.every(r=>r.status==='fulfilled');error.textContent='Some balances could not be loaded. Open your wallet to retry.';
}
wallet();document.addEventListener('visibilitychange',()=>{if(!document.hidden)wallet();});
