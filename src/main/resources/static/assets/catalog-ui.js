import {buyProduct,checkoutBusy} from './purchase-checkout.js';
export function element(tag, className, text) {
  const node = document.createElement(tag); if (className) node.className = className;
  if (text !== undefined) node.textContent = text; return node;
}
export const money = value => new Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits:2}).format(Number(value));
export function image(url, title, className) {
  const img = element('img', className); img.alt = title; img.loading = 'lazy'; img.referrerPolicy = 'no-referrer'; img.draggable = false; img.addEventListener('contextmenu', event => event.preventDefault()); img.addEventListener('dragstart', event => event.preventDefault());
  img.addEventListener('error', () => img.replaceWith(element('div', 'image-placeholder', 'Image unavailable')), {once:true});
  img.src = url; return img;
}
export function prices(product) {
  const row = element('div', 'price-row'); row.append(element('span', 'price', money(product.discountPrice)), element('s', 'original-price', money(product.originalPrice))); return row;
}
export function stats(product) {
  const list = element('dl', 'product-stats');
  const range = (min, max) => Number(min) === Number(max) ? money(min) : `${money(min)} \u2013 ${money(max)}`;
  for (const [label, value] of [['Total claims', product.totalClaims], ['Daily earning', range(product.minimumDailyIncome, product.maximumDailyIncome)], ['Earning potential', range(Number(product.minimumDailyIncome) * Number(product.totalClaims), product.totalEarnings)]]) {
    const group = element('div'); group.append(element('dt', '', label), element('dd', '', value)); list.append(group);
  } return list;
}
function markSoldOut(card){
  if(card.dataset.purchased==='true'||card.querySelector('.sold-out-badge'))return;
  card.querySelector('.card-top').append(element('span','status-pill inactive sold-out-badge','STOPPED'));
  const button=card.querySelector('[data-buy]');button.textContent='STOPPED';button.disabled=true;button.dataset.unavailable='true';
}
function countryCode(name){const map={japan:'jp',germany:'de','united states':'us',usa:'us',india:'in',unitedkingdom:'gb','united kingdom':'gb',uk:'gb',france:'fr',canada:'ca',australia:'au',italy:'it',spain:'es',singapore:'sg',uae:'ae','united arab emirates':'ae'};return map[name.trim().toLowerCase()]||'';}
export function productCard(product, admin = false, purchaseId = null) {
  const card = element('article', 'product-card'); const visual = element('div','product-visual'); visual.append(image(product.imageUrl, product.title, 'product-image')); if(product.tag?.trim()) (()=>{const tag=element('span','product-tag',product.tag.trim()); const length=product.tag.trim().length; tag.classList.toggle('tag-medium',length>5&&length<9); tag.classList.toggle('tag-long',length>=9); visual.append(tag);})(); if(product.countryName?.trim()){ const meta=element('div','product-country'); const code=countryCode(product.countryName); if(code){const flag=element('img','country-flag');flag.src=`https://flagcdn.com/w40/${code}.png`;flag.alt='';flag.loading='lazy';meta.append(flag);} meta.append(element('span','',product.countryName.trim())); visual.append(meta);} card.append(visual);
  const body = element('div', 'card-body'), top = element('div', 'card-top');
  top.append(element('h3', '', product.title));
  const createdAt = Date.parse(product.createdAt || '');
  if (Number.isFinite(createdAt) && Date.now() - createdAt <= 48 * 60 * 60 * 1000) top.append(element('span', 'new-badge', 'NEW'));
  if (admin) top.append(element('span', `status-pill ${product.active?'':'inactive'}`, product.active?'Active':'Stopped'));
  
  if(!admin&&!purchaseId&&!product.soldOut)top.append(element('span','status-pill','ACTIVE'));
  body.append(top, prices(product), stats(product));
  if (!admin) { const actions=element('div','product-actions'); const link = element('a', 'button primary view-product', 'View Product →'); link.href = `/products/${product.id}`; actions.append(link); body.append(actions); }
  if(!admin){
    card.dataset.purchased=String(Boolean(purchaseId));
    const buy=element('button','button primary',purchaseId?'SOLD':'BUY');buy.type='button';buy.dataset.buy=product.id;
    buy.dataset.unavailable=String(Boolean(purchaseId));buy.disabled=Boolean(purchaseId)||checkoutBusy();
    buy.addEventListener('click',()=>buyProduct(product.id));const actions=body.querySelector('.product-actions');if(actions)actions.append(buy);else body.append(buy);
    if(purchaseId){top.append(element('span','status-pill inactive sold-badge','SOLD'));const progressLink=element('a','product-progress-link','View claim progress');progressLink.href='/features/my-product/'+purchaseId;body.append(progressLink);}
  }
  card.append(body);if(!admin){if(product.soldOut)markSoldOut(card);} return {card, body};
}

