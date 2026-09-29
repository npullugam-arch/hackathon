import './advertisements.js';
import {post} from './api.js';
import {currentUser} from './session-user.js';
const sidebar=document.getElementById('app-sidebar'),toggle=document.querySelector('.menu-toggle'),backdrop=document.querySelector('.drawer-backdrop'),mobile=matchMedia('(max-width: 767px)');
const main=document.querySelector('main'),footer=document.querySelector('.site-footer'),bottom=document.querySelector('.mobile-bottom-nav');
let opened=false;
function menu(open,restore=true){
  opened=open;sidebar.classList.toggle('open',open);sidebar.inert=mobile.matches&&!open;backdrop.hidden=!open;
  toggle.setAttribute('aria-expanded',String(open));toggle.setAttribute('aria-label',open?'Close menu':'Open menu');
  document.body.classList.toggle('drawer-open',open);
  for(const node of [main,footer,bottom])if(node)node.inert=open;
  if(open)sidebar.querySelector('.drawer-close').focus();else if(restore)toggle.focus();
}
toggle.addEventListener('click',()=>menu(!opened));document.querySelector('.drawer-close').addEventListener('click',()=>menu(false));backdrop.addEventListener('click',()=>menu(false));
mobile.addEventListener('change',()=>menu(false,false));sidebar.inert=mobile.matches;
document.addEventListener('keydown',event=>{
  if(!opened)return;if(event.key==='Escape'){event.preventDefault();menu(false);}
  if(event.key==='Tab'){const nodes=[...sidebar.querySelectorAll('a,button')].filter(n=>!n.disabled),first=nodes[0],last=nodes.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}
});
sidebar.addEventListener('click',e=>{if(e.target.closest('a')&&opened)menu(false,false);});
function activeLinks(){for(const link of document.querySelectorAll('#main-navigation a,.mobile-bottom-nav a')){
 const match=link.hash?location.pathname===link.pathname&&location.hash===link.hash:location.pathname===link.pathname||link.pathname==='/products'&&location.pathname.startsWith('/products/')||link.pathname==='/features/my-product'&&location.pathname.startsWith('/features/my-product/');
 if(match)link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');
}const active=document.querySelector('#main-navigation [aria-current]');document.getElementById('shell-page-title').textContent=active?.textContent.trim()||document.title.split(/[|?]/)[0].trim();}
activeLinks();window.addEventListener('hashchange',activeLinks);
currentUser().then(user=>{const name=user.name||user.email?.split('@')[0]||'My account';for(const node of document.querySelectorAll('[data-shell-name]'))node.textContent=name;for(const node of document.querySelectorAll('[data-shell-initial]'))node.textContent=name.charAt(0).toUpperCase();}).catch(()=>{});
const channel='BroadcastChannel' in window?new BroadcastChannel('launchpad-auth'):null;
if(channel)channel.onmessage=e=>{if(e.data==='logout')location.replace('/login');};
let leaving=false;
for(const button of document.querySelectorAll('[data-shell-logout]'))button.addEventListener('click',async()=>{
 if(leaving)return;leaving=true;const buttons=document.querySelectorAll('[data-shell-logout]');buttons.forEach(b=>b.disabled=true);
 try{await post('/api/auth/logout');channel?.postMessage('logout');location.replace('/?loggedOut=1');}
 catch(error){const node=document.getElementById('shell-message');node.textContent=error.message;node.hidden=false;leaving=false;buttons.forEach(b=>b.disabled=false);}
});
window.addEventListener('pageshow',e=>{if(e.persisted)location.reload();});
