import {request} from './api.js';
import {element} from './catalog-ui.js';
if(!document.querySelector('link[href=\"/assets/advertisements.css\"]')){const style=document.createElement('link');style.rel='stylesheet';style.href='/assets/advertisements.css';document.head.append(style);}
const dialog=element('dialog','ad-modal'), heading=element('header'), title=element('h2'), close=element('button','icon-button'), media=element('div','ad-media');
dialog.setAttribute('aria-label','Announcement');close.type='button';close.setAttribute('aria-label','Close advertisement');heading.append(title,close);
const picture=element('img');picture.referrerPolicy='no-referrer';const notice=element('p','ad-notice','This advertisement image could not be loaded.');notice.hidden=true;
media.append(picture);dialog.append(heading,media,notice);document.body.append(dialog);
// One impression per tab session, shared across internal page loads and reloads.
// Do not key this by ad revision: admin edits must not reopen a dismissed popup.
const sessionKey='launchpad.ad.shown.v2';
let shown=false, loading=false;
function alreadyShown(){ return shown; }
function rememberShown(){
 shown=true;
 try{sessionStorage.setItem(sessionKey,'true');}catch{/* Keep this document usable if storage is blocked. */}
}
function hide(){dialog.close();}
close.addEventListener('click',hide);dialog.addEventListener('cancel',event=>{event.preventDefault();hide();});
picture.addEventListener('error',()=>{picture.hidden=true;notice.hidden=false;});
async function refresh(){
 if(loading||document.hidden||alreadyShown())return;loading=true;
 try{
  const result=await request('/api/advertisements/current'), ad=result.items[0];
  if(!ad||alreadyShown())return;
  title.textContent=ad.title;picture.alt=ad.title;picture.hidden=false;notice.hidden=true;picture.src=ad.imageUrl;
  dialog.showModal();rememberShown();
 }catch(error){if(error.status===401){dialog.close();location.replace('/login');}}
 finally{loading=false;}
}
close.innerHTML='<svg aria-hidden=\"true\" viewBox=\"0 0 24 24\"><path d=\"M6 6l12 12M18 6L6 18\"/></svg>';
refresh();setInterval(refresh,15000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});

