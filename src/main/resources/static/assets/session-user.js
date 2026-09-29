import {request} from './api.js';
let pending;
export function currentUser(){
  if(!pending)pending=request('/api/auth/me').catch(error=>{pending=null;throw error;});
  return pending;
}
document.addEventListener('visibilitychange',()=>{if(!document.hidden)pending=null;});
