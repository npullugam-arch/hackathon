import { request, showMessage } from './api.js';
import { adminWrite } from './admin-api.js';
import './admin-recharges.js';
import './admin-withdrawals.js';
import './admin-purchases.js';
import './admin-invitations.js';
import './admin-support.js';
import './admin-photo-tasks.js';
import './admin-users.js?v=3';
import { element, image, productCard, money } from './catalog-ui.js';
const $ = id => document.getElementById(id);
const productForm = $('product-form'), adForm = $('ad-form'), machineForm=$('machine-form');
let products = [], ads = [], machines = [], busy = false, pendingDelete;
function lock(value) { busy = value; for (const button of document.querySelectorAll('.section-tabs button, #admin-logout, #products-section button, #ads-section button, #machines-section button, #purchases-section button, #product-dialog button, #ad-dialog button, #machine-dialog button, #delete-dialog button')) button.disabled = value; }
function failure(error) { if (error.status === 401) location.replace('/admin'); else showMessage(error.message); }
function action(label, handler, kind = 'secondary') { const button = element('button', `button ${kind}`, label); button.type = 'button'; button.addEventListener('click', handler); return button; }
function formError(id, text = '') { $(id).textContent = text; $(id).hidden = !text; }
async function load() {
  try {
    const results = await Promise.allSettled([request('/api/admin/products'), request('/api/admin/advertisements'),request('/api/admin/machines')]);
    if(results[0].status==='fulfilled') products=results[0].value;
    if(results[1].status==='fulfilled') ads=results[1].value;
    if(results[2].status==='fulfilled') machines=results[2].value;
    for(const result of results) if(result.status==='rejected') failure(result.reason);
    renderMachines();
    $('product-count').textContent = products.length; $('ad-count').textContent = ads.length;
    $('admin-products').replaceChildren(); $('admin-ads').replaceChildren();
    for (const p of products) {
      const {card, body} = productCard(p, true); const controls = element('div', 'card-actions');
      controls.append(action(p.active?'Stop':'Activate', () => toggleProduct(p), 'primary'), action('Edit', () => openProduct(p)), action('Delete', () => confirmDelete('products', p), 'secondary')); body.append(controls); $('admin-products').append(card);
    }
    for (const ad of ads) {
      const card = element('article', 'product-card'), body = element('div', 'card-body'), top = element('div', 'card-top');
      top.append(element('h3', '', ad.title), element('span', `status-pill ${ad.active?'':'inactive'}`, ad.active?'Active':'Inactive'));
      const controls = element('div','card-actions'); controls.append(action(ad.active?'Deactivate':'Activate', () => toggleAd(ad), 'primary'), action('Edit', () => openAd(ad)), action('Delete', () => confirmDelete('advertisements', ad)));
      body.append(top, controls); card.append(image(ad.imageUrl, ad.title, 'ad-preview'), body); $('admin-ads').append(card);
    }
    $('products-empty').hidden = products.length > 0; $('ads-empty').hidden = ads.length > 0;
  } catch (error) { failure(error); }
  finally { $('products-loading').hidden = true; $('ads-loading').hidden = true; $('machines-loading').hidden=true; }
}
function computePreview() {
  const claims = Number(productForm.elements.totalClaims.value), daily = Number(productForm.elements.maximumDailyIncome.value);
  $('total-earnings').value = Number.isFinite(daily*claims) ? money(Number(productForm.elements.minimumDailyIncome.value)*claims)+' – '+money(daily*claims) : '';
}
function openProduct(p) {
  if (busy) return; productForm.reset(); formError('product-error');
  productForm.elements.id.value = p?.id || '';
  for (const key of ['title','imageUrl','originalPrice','discountPrice','totalClaims','minimumDailyIncome','maximumDailyIncome','description','countryName','countryUrl','tag']) productForm.elements[key].value = p?.[key] ?? '';
  productForm.elements.active.checked = p?.active ?? false;
  $('product-form-title').textContent = p ? 'Edit product' : 'Add product'; computePreview(); $('product-dialog').showModal();
}
function openAd(ad) {
  if (busy) return; adForm.reset(); formError('ad-error'); adForm.elements.id.value = ad?.id || '';
  adForm.elements.title.value = ad?.title || ''; adForm.elements.imageUrl.value = ad?.imageUrl || ''; adForm.elements.active.checked = ad?.active || false;
  $('ad-form-title').textContent = ad ? 'Edit advertisement' : 'Add advertisement'; $('ad-dialog').showModal();
}
productForm.addEventListener('input', computePreview);
productForm.addEventListener('submit', async event => {
  event.preventDefault(); if (busy) return;
  const f = productForm.elements; const id = f.id.value;
  if (Number(f.discountPrice.value)>Number(f.originalPrice.value)) return formError('product-error','Discount price cannot exceed original price.');
  if(Number(f.minimumDailyIncome.value)>Number(f.maximumDailyIncome.value))return formError('product-error','Minimum daily income cannot exceed maximum daily income.');
  const payload = {title:f.title.value.trim(), imageUrl:f.imageUrl.value.trim(), originalPrice:f.originalPrice.value, discountPrice:f.discountPrice.value,
    totalClaims:Number(f.totalClaims.value), minimumDailyIncome:f.minimumDailyIncome.value, maximumDailyIncome:f.maximumDailyIncome.value, description:f.description.value.trim(), countryName:f.countryName.value.trim(), countryUrl:f.countryUrl.value.trim(), tag:f.tag.value.trim(), active:f.active.checked};
  lock(true); formError('product-error');
  try { await adminWrite(`/api/admin/products${id?'/'+id:''}`, payload, id?'PUT':'POST'); $('product-dialog').close(); showMessage('Product saved successfully.','success'); await load(); }
  catch(error) { if(error.status===401) failure(error); else formError('product-error',error.message); }
  finally { lock(false); }
});
adForm.addEventListener('submit', async event => {
  event.preventDefault(); if(busy) return; const f=adForm.elements, id=f.id.value; lock(true); formError('ad-error');
  try { await adminWrite(`/api/admin/advertisements${id?'/'+id:''}`, {title:f.title.value.trim(),imageUrl:f.imageUrl.value.trim(),active:f.active.checked}, id?'PUT':'POST'); $('ad-dialog').close(); showMessage('Advertisement saved successfully.','success'); await load(); }
  catch(error) { if(error.status===401) failure(error); else formError('ad-error',error.message); }
  finally { lock(false); }
});
async function toggleProduct(p) {
  if(busy)return; lock(true);
  const payload={};for(const key of ['title','imageUrl','originalPrice','discountPrice','totalClaims','minimumDailyIncome','maximumDailyIncome','description','countryName','countryUrl','tag'])payload[key]=p[key];payload.active=!p.active;
  try {await adminWrite(`/api/admin/products/${p.id}`,payload,'PUT');showMessage(p.active?'Product stopped. New purchases are unavailable.':'Product activated.','success');await load();}
  catch(error){failure(error);}finally{lock(false);}
}
async function toggleAd(ad) {
  if(busy)return; lock(true);
  try { await adminWrite(`/api/admin/advertisements/${ad.id}`, {title:ad.title,imageUrl:ad.imageUrl,active:!ad.active},'PUT'); showMessage(ad.active?'Advertisement deactivated.':'Advertisement activated. Users will see this announcement.','success'); await load(); }
  catch(error){failure(error);} finally{lock(false);}
}
function confirmDelete(type,item) { if(busy)return; pendingDelete={type,id:item.id}; $('delete-description').textContent=`“${item.title || item.name}” will be permanently removed. This cannot be undone.`; formError('delete-error'); $('delete-dialog').showModal(); }
$('confirm-delete').addEventListener('click', async () => {
  if(busy||!pendingDelete)return; lock(true);
  try { await adminWrite(`/api/admin/${pendingDelete.type}/${pendingDelete.id}`, {}, 'DELETE'); $('delete-dialog').close(); pendingDelete=null; showMessage('Item deleted.','success'); await load(); }
  catch(error){if(error.status===401)failure(error);else formError('delete-error',error.message);} finally{lock(false);}
});
$('cancel-delete').addEventListener('click',()=>$('delete-dialog').close());
for(const button of document.querySelectorAll('[data-close]'))button.addEventListener('click',()=>{if(!busy)$(button.dataset.close).close();});
for(const dialog of document.querySelectorAll('dialog'))dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
$('add-product').addEventListener('click',()=>openProduct()); $('add-ad').addEventListener('click',()=>openAd());
for(const [tab,section] of [['products-tab','products-section'],['ads-tab','ads-section'],['machines-tab','machines-section'],['purchases-tab','purchases-section'],['recharges-tab','recharges-section'],['withdrawals-tab','withdrawals-section'],['invitations-tab','invitations-section'],['support-tab','support-section'],['photo-tasks-tab','photo-tasks-section']])$(tab).addEventListener('click',()=>{
  for(const id of ['products-tab','ads-tab','machines-tab','purchases-tab','recharges-tab','withdrawals-tab','invitations-tab','support-tab','photo-tasks-tab'])$(id).setAttribute('aria-pressed',String(id===tab));
  for(const id of ['products-section','ads-section','machines-section','purchases-section','recharges-section','withdrawals-section','invitations-section','support-section','photo-tasks-section'])$(id).hidden=id!==section;
  document.dispatchEvent(new CustomEvent('admin-section-change',{detail:section}));
});
if(location.hash==='#recharge-transactions')$('recharges-tab').click();
if(location.hash==='#withdrawals')$('withdrawals-tab').click();
$('admin-logout').addEventListener('click',async()=>{if(busy)return;lock(true);try{await adminWrite('/api/admin/logout');location.replace('/admin');}catch(error){failure(error);lock(false);}});
window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
load();

function renderMachines(){
 $('machine-count').textContent=machines.length;$('admin-machines').replaceChildren();$('machines-empty').hidden=machines.length>0;
 for(const m of machines){const card=element('article','product-card machine-card'),body=element('div','card-body'),top=element('div','card-top');
 top.append(element('h3','',m.name),element('span',`status-pill ${m.active?'':'inactive'}`,m.active?'Active':'Inactive'));
 const controls=element('div','card-actions');controls.append(action('Edit',()=>openMachine(m)),action('Delete',()=>confirmDelete('machines',m)));
 body.append(top,element('p','eyebrow',m.profileTitle),element('p','muted',m.shortDescription),controls);card.append(image(m.imageUrl,m.name,'product-image'),body);$('admin-machines').append(card);}
}
function openMachine(m){if(busy)return;machineForm.reset();formError('machine-error');machineForm.elements.id.value=m?.id||'';
 for(const key of ['name','imageUrl','profileTitle','shortDescription','fullDetails'])machineForm.elements[key].value=m?.[key]||'';
 machineForm.elements.active.checked=m?.active||false;$('machine-form-title').textContent=m?'Edit machine':'Add machine';$('machine-dialog').showModal();}
$('add-machine').addEventListener('click',()=>openMachine());
machineForm.addEventListener('submit',async event=>{event.preventDefault();if(busy)return;const f=machineForm.elements,id=f.id.value,payload={active:f.active.checked};
 for(const key of ['name','imageUrl','profileTitle','shortDescription','fullDetails'])payload[key]=f[key].value.trim();lock(true);formError('machine-error');
 try{await adminWrite(`/api/admin/machines${id?'/'+id:''}`,payload,id?'PUT':'POST');$('machine-dialog').close();showMessage('Machine saved successfully.','success');await load();}
 catch(error){if(error.status===401)failure(error);else formError('machine-error',error.message);}finally{lock(false);}});

$('product-image-upload').addEventListener('change',async event=>{
  const file=event.target.files[0];if(!file||busy)return;
  if(file.size>5*1024*1024||!['image/png','image/jpeg'].includes(file.type))return formError('product-error','Choose a PNG or JPEG no larger than 5 MB.');
  lock(true);event.target.disabled=true;formError('product-error','Uploading image...');
  try{const csrf=await request('/api/admin/csrf'),data=new FormData();data.append('file',file);
    const result=await request('/api/admin/product-images',{method:'POST',headers:{[csrf.headerName]:csrf.token},body:data});productForm.elements.imageUrl.value=result.imageUrl;formError('product-error');
  }catch(ex){formError('product-error',ex.message);}finally{lock(false);event.target.disabled=false;}
});
