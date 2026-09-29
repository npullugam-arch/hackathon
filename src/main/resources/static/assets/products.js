import {request, showMessage} from './api.js';
import {element, image, prices, stats, productCard} from './catalog-ui.js';
import {buyProduct} from './purchase-checkout.js';
import './advertisements.js';
const $=id=>document.getElementById(id);
async function load() {
 try {
  const id=location.pathname.split('/')[2];
  if(!id){const result=await request('/api/products');$('products-grid').replaceChildren(...result.items.map(p=>productCard(p,false,result.purchases?.[p.id]).card));$('catalog-empty').hidden=result.items.length>0;}
  else {
   $('page-heading').hidden=true;$('back-products').hidden=false;
   const result=await request('/api/products/'+encodeURIComponent(id)); const p=result.product;
   document.title=`${p.title} · Launchpad`;const layout=element('div','details-layout'), info=element('div','details-info');
    const wallet=await request('/api/wallet');
    info.append(element('p','eyebrow','PRODUCT DETAILS'),element('h1','',p.title),prices(p),stats(p),element('p','balance-callout',`Recharge Balance: ${new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(wallet.balancePaise/100)}`));
    const owned=Boolean(result.purchases?.[p.id]);const buy=element('button','button primary',owned?'SOLD':'BUY');buy.dataset.purchased=String(owned);buy.type='button';buy.dataset.buy=p.id;buy.dataset.unavailable=String(owned||p.soldOut);buy.disabled=owned||p.soldOut;if(owned||p.soldOut){buy.textContent=owned?'SOLD':'STOPPED';info.append(element('span','status-pill inactive',buy.textContent));}buy.addEventListener('click',()=>buyProduct(p.id));info.append(buy);
   layout.append(image(p.imageUrl,p.title,'details-image'),info);
   const details=element('section','description-panel'); details.append(element('h2','','About this product'),element('p','product-description',p.description));
   $('product-details').replaceChildren(layout,details);$('product-details').hidden=false;
  }
 }catch(error){if(error.status===401)return location.replace('/login');showMessage(error.status===404?'This product is inactive or no longer available.':error.message);}
 finally{$('catalog-loading').hidden=true;}
}
window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
document.addEventListener('visibilitychange',()=>{if(!document.hidden)load();});
load();

document.addEventListener('purchase-state-changed',load);
