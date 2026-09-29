import {request} from './api.js';
import {invitationRows} from './commerce-ui.js';
const $=id=>document.getElementById(id);let page=0,pages=0,loading=false,filters=new URLSearchParams();
function controls(){$('ai-refresh').disabled=loading;$('ai-previous').disabled=loading||page===0;$('ai-next').disabled=loading||page+1>=pages;}
async function load(){if(loading)return;loading=true;controls();$('ai-error').hidden=true;
  try{const query=new URLSearchParams(filters);query.set('page',page);const data=await request('/api/admin/invitations?'+query);page=data.page;pages=data.totalPages;invitationRows($('ai-rows'),data.items,true);$('ai-empty').hidden=data.items.length>0;$('ai-empty').textContent='No invitations match these filters.';$('ai-page').textContent=`${data.total} invitations | Page ${pages?page+1:0} of ${pages}`;}
  catch(ex){if(ex.status===401)location.replace('/admin');else{$('ai-error').hidden=false;$('ai-error').textContent=ex.message;}}finally{loading=false;controls();}}
$('ai-filters').addEventListener('submit',event=>{event.preventDefault();if(loading)return;filters=new URLSearchParams();for(const [key,value] of new FormData(event.currentTarget))if(value.trim())filters.set(key,value.trim());page=0;load();});
$('ai-refresh').addEventListener('click',load);$('ai-previous').addEventListener('click',()=>{page--;load();});$('ai-next').addEventListener('click',()=>{page++;load();});
document.addEventListener('admin-section-change',event=>{if(event.detail==='invitations-section')load();});
