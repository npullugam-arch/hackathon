export function confirmAction(message,title='Confirm action'){
  return new Promise(resolve=>{
    const dialog=document.createElement('dialog');dialog.className='editor-dialog compact';
    const heading=document.createElement('h2');heading.textContent=title;
    const description=document.createElement('p');description.textContent=message;
    const actions=document.createElement('div');actions.className='dialog-actions';
    const cancel=document.createElement('button');cancel.type='button';cancel.className='button secondary';cancel.textContent='Cancel';
    const accept=document.createElement('button');accept.type='button';accept.className='button primary';accept.textContent='Confirm';
    let settled=false;const finish=value=>{if(settled)return;settled=true;dialog.close();dialog.remove();resolve(value);};
    cancel.addEventListener('click',()=>finish(false));accept.addEventListener('click',()=>finish(true));dialog.addEventListener('cancel',event=>{event.preventDefault();finish(false);});
    actions.append(cancel,accept);dialog.append(heading,description,actions);document.body.append(dialog);dialog.showModal();cancel.focus();
  });
}
