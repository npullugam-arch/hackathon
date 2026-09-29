import {post,showMessage} from './api.js';
let busy=false;
export const checkoutBusy=()=>busy;
function controls(value){busy=value;for(const button of document.querySelectorAll('[data-buy],[data-resume]'))button.disabled=value||button.dataset.unavailable==='true';}
export async function buyProduct(productId){
  if(busy)return;
  controls(true);showMessage('Checking your Recharge Balance…','info');
  try{
    const result=await post('/api/purchases',{productId});
    showMessage(`Product purchased. Recharge Balance remaining: ${new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(result.rechargeBalancePaise/100)}.`,'success');
    location.assign('/features/my-product');
  }catch(ex){if(ex.status===401)location.replace('/login');else showMessage(ex.message);}
  finally{controls(false);document.dispatchEvent(new CustomEvent('purchase-state-changed'));}
}
export function resumePurchase(){showMessage('Product purchases use Recharge Balance. Choose the product again to retry safely.','info');}
