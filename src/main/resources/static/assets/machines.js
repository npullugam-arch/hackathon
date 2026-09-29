import {request} from './api.js';
import {element,image} from './catalog-ui.js';
export function machineCard(m){const card=element('article','product-card machine-card'),body=element('div','card-body');
body.append(element('p','eyebrow',m.profileTitle),element('h3','',m.name),element('p','muted',m.shortDescription));
const facts=element('dl','machine-facts');
for(const [label,value] of [['Collection','Task Bonus'],['Status',m.active?'Active':'Inactive']]){const item=element('div');item.append(element('dt','',label),element('dd','',value));facts.append(item);}body.append(facts);
const link=element('a','button secondary','View Details');link.href=m.taskType==='TAKE_PHOTO'?'/tasks/take-photo':m.taskType==='REFER_EARN'?'/tasks/refer-earn':`/machines/${m.id}`;body.append(link);card.append(image(m.imageUrl,m.name,'product-image'),body);return card;}
const grid=document.getElementById('machines-grid'),detail=document.getElementById('machine-details');let loading=false,revision='';
async function load(){if(loading||document.hidden||(!grid&&!detail))return;loading=true;
try{const result=await request(grid?'/api/machines':`/api/machines/${encodeURIComponent(location.pathname.split('/').pop())}`);const next=JSON.stringify(result);
if(next!==revision){revision=next;if(grid){grid.replaceChildren(...result.items.map(machineCard));document.getElementById('machines-empty').hidden=result.items.length>0;}
else{const box=element('article','machine-detail'),body=element('div','card-body');body.append(element('p','eyebrow',result.profileTitle),element('h1','',result.name),element('p','muted',result.shortDescription),element('h2','','Full details'),element('p','full-details',result.fullDetails),element('span','preview-label','Task Bonus preview'));if(['TAKE_PHOTO','REFER_EARN'].includes(result.taskType)){const taskLink=element('a','button primary',result.taskType==='TAKE_PHOTO'?'Open Take Photo task':'Open Refer & Earn');taskLink.href=result.taskType==='TAKE_PHOTO'?'/tasks/take-photo':'/tasks/refer-earn';body.append(taskLink);}box.append(image(result.imageUrl,result.name,'product-image'),body);detail.replaceChildren(box);}}
document.getElementById('machines-error').hidden=true;
}catch(e){if(e.status===401)return location.replace('/login');if(detail){detail.replaceChildren();revision='';}const error=document.getElementById('machines-error');error.textContent=e.message;error.hidden=false;}
finally{loading=false;document.getElementById('machines-loading').hidden=true;}}
load();setInterval(load,15000);document.addEventListener('visibilitychange',load);
