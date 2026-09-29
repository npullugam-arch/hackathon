import {element} from './catalog-ui.js';
export const money=paise=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(Number(paise)/100);
export const date=value=>value?new Date(value).toLocaleString(undefined,{dateStyle:'medium',timeStyle:'short'}):'—';
export function button(text,handler,kind='secondary'){const b=element('button',`button ${kind}`,text);b.type='button';b.addEventListener('click',handler);return b;}
export function badge(status){return element('span','commerce-badge '+status.toLowerCase(),status[0]+status.slice(1).toLowerCase());}
export function detail(list,label,value){list.append(element('dt','',label),element('dd','',value??'—'));}
export function purchaseDetails(list,p){list.replaceChildren();for(const [label,value]of[
  ['Purchase ID',p.id],['Product',p.productTitle],['Product ID',p.productId],['User',p.userName || p.userId],['User ID',p.userId],['Payment status',p.paymentStatus],['Lifecycle status',p.status],
  ['Purchase amount',money(p.purchaseAmountPaise)],['Payment reference',p.paymentId],['Provider order',p.orderId],['Purchased',date(p.purchaseDate)],['First claim / start',date(p.startDate)],['End (exclusive)',date(p.endDate)],
  ['Daily claim time',`${p.claimTime} · ${p.claimZone}`],['Duration',`${p.durationDays} days`],['Completed days',p.completedDays],['Remaining days',p.remainingDays],['Claimed days',p.claimedDays],
  ['Daily earning range',money(p.minimumDailyIncomePaise)+' – '+money(p.maximumDailyIncomePaise)], ["Today's Profit",money(p.todayProfitPaise)],['Maximum earning potential',money(p.totalExpectedPaise)],['Income released to date',money(p.currentEarningsPaise)],
  ['Claimed income',money(p.claimedIncomePaise)],['Available to claim',money(p.claimablePaise)],['Expired unclaimed income',money(p.missedIncomePaise)],['Remaining earning potential (up to)',money(p.remainingEarningsPaise)]])detail(list,label,value);}
export function claimsRows(target,claims){target.replaceChildren();for(const c of claims){const row=element('tr');for(const v of [c.dayNumber,money(c.amountPaise),date(c.eligibleAt),date(c.claimedAt),c.ledgerId])row.append(element('td','',v));target.append(row);}}
export function invitationRows(target,items,admin=false){target.replaceChildren();for(const i of items){const row=element('tr');const values=admin?
  [i.inviterName || 'Unknown inviter',i.inviteeName || 'Unknown user',i.referralCode,date(i.registrationDate),i.productTitle || 'No verified purchase',i.purchaseAmountPaise==null?'—':money(i.purchaseAmountPaise),i.purchaseStatus,money(i.inviterRewardPaise),money(i.inviteeRewardPaise),i.rewardStatus]:
  [i.inviteeName || 'Member',i.inviteeId,date(i.registrationDate),i.productTitle || 'No verified purchase',i.purchaseStatus,i.rewardStatus,money(i.inviterRewardPaise)];
  for(const value of values){const cell=element('td');if(['PAID','LOCKED','PENDING','COMPLETED','CLAIMED','CREDITED'].includes(value))cell.append(badge(value));else cell.textContent=value;row.append(cell);}target.append(row);}}
