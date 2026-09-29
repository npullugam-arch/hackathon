import {request} from './api.js';
import {productCard} from './catalog-ui.js';
const $=id=>document.getElementById(id);
async function load(){try{const result=await request('/api/products');$('home-products-grid').replaceChildren(...result.items.map(p=>productCard(p,false,result.purchases?.[p.id]).card));$('home-products-empty').hidden=result.items.length>0;}
catch(ex){$('home-products-empty').hidden=false;$('home-products-empty').textContent=ex.message;}finally{$('home-products-loading').hidden=true;}}
load();document.addEventListener('visibilitychange',()=>{if(!document.hidden)load();});

document.addEventListener('purchase-state-changed',load);
