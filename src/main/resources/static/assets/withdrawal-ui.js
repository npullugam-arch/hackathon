export const money = value => new Intl.NumberFormat('en-IN', {style:'currency',currency:'INR'}).format(Number(value)/100);
export const date = value => value ? new Date(value).toLocaleString(undefined,{dateStyle:'medium',timeStyle:'short'}) : '—';
export function element(tag, text='', className='') { const node=document.createElement(tag); node.textContent=text; node.className=className; return node; }
export function detail(list, label, value) { list.append(element('dt',label),element('dd',value || '—')); }
export function badge(item) { return element('span',item.status==='REFUNDED' ? 'Refunded · '+(item.failureKind==='REJECTED' ? 'cancelled' : item.failureKind.toLowerCase()) : item.status[0]+item.status.slice(1).toLowerCase(),'withdraw-badge '+item.status.toLowerCase()); }
export function historyRow(item) {
  const row=element('tr');
  const amount=element('td',money(item.amountPaise)); amount.append(element('small',item.id,'withdraw-ref'));
  const bank=element('td',item.bankAccount.bankName); bank.append(element('small',item.bankAccount.maskedAccountNumber));
  const status=element('td'); status.append(badge(item));
  if(item.status==='REFUNDED') status.append(element('small',money(item.amountPaise)+' returned to Winning Cash'));
  const reference=element('td',item.referenceId || '—');
  if(item.adminRemark) reference.append(element('small',item.adminRemark));
  if(item.failureReason) reference.append(element('small',item.failureReason));
  row.append(amount,bank,element('td',date(item.requestedAt)),status,reference,element('td',date(item.completedAt || item.rejectedAt || item.processedAt)));
  return row;
}
