import {money} from './commerce-ui.js';
export function cycleText(cycle){return cycle?`${money(cycle.accruedPaise)} / ${money(cycle.finalAmountPaise)}`:'Available after purchase';}
export function claimStatus(p,now){
  if(p.dailyEarningCycle?.status==='ACTIVE'&&now>=new Date(p.dailyEarningCycle.endsAt).getTime())return 'Refreshing Daily Earning Cycle…';
  if(p.claimablePaise>0)return 'Daily claim available now';
  if(p.status==='COMPLETED')return p.claimedDays===p.durationDays?'All daily claims completed':'Earning period ended';
  if(p.paymentStatus!=='PAID')return 'Complete purchase to claim';
  if(!p.nextClaimAt)return 'No further daily claims';
  return `Next daily claim: ${new Date(p.nextClaimAt).toLocaleString()}`;
}
