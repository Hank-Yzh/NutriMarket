import {api} from './api.js';
import {requireUser,node,table,time,uuid} from './dietary-ui.js';
import {$,base,states,storeStates,lines,money,chip,runner} from './procurement-ui.js';
import {newRecordId} from './upload-crypto.js';
const id=new URLSearchParams(location.search).get('id'),path=base+'/orders/'+encodeURIComponent(id);let current,maxAttempts=3;
const failed=['PARTIALLY_FULFILLED','REJECTED'];
const outcomes={SUBMITTING:'Submitting to the stores. Reload in a moment.',CONFIRMED:'Every store accepted the order.',PARTIALLY_FULFILLED:'Some items were rejected. The accepted store orders stay open until you choose an option below.',
    REJECTED:'The store rejected the order. Nothing will be fulfilled until you choose an option below.',REOPTIMISING:'Re-optimising: the basket was rebuilt without the rejected products. Review it and submit again.',
    CANCELLED:'This order was cancelled. Nothing will be fulfilled.',LOCAL_CONFIRMED:'Recorded as a local confirmation before store submission existed.',LOCAL_CANCELLED:'This local confirmation was cancelled.'};
const tone=s=>({CONFIRMED:'good',ACCEPTED:'good',PARTIALLY_FULFILLED:'warn',PARTIAL:'warn',REJECTED:'bad',CANCELLED:'muted',LOCAL_CANCELLED:'muted',REOPTIMISING:'warn'}[s]||'');
const work=runner(()=>controls());
function controls(){if(!current)return;const max=maxAttempts,open=current.storeOrders.filter(s=>!['ACCEPTED','CANCELLED'].includes(s.status));
    $('#actions-panel').hidden=!failed.includes(current.status);$('#recover').disabled=!open.length||open.every(s=>s.attempts>=max);
    $('#recover-help').textContent=$('#recover').disabled?'Recovery attempts are exhausted. Re-optimise or cancel.':'Resubmit only the rejected items to the same store.';
    $('#cancel').hidden=!['CONFIRMED','LOCAL_CONFIRMED','REOPTIMISING'].includes(current.status);}
function dl(container,rows){container.replaceChildren(...rows.flatMap(([k,v])=>{const dd=node('dd');if(v instanceof Node)dd.append(v);else dd.textContent=v;return [node('dt',k),dd];}));}
function anchor(label,url){const a=node('a',label);a.href=url;return a;}
function render(o){current=o;$('#content').hidden=false;const b=o.snapshot,c=o.currency;
    $('#order-number').textContent=o.orderId;$('#status').replaceWith(Object.assign(chip(states[o.status]||o.status,tone(o.status)),{id:'status'}));
    $('#meta').textContent=`Placed ${time(o.createdAt)}${o.submission>1?` · submission ${o.submission}`:''}${o.cancelledAt?' · cancelled '+time(o.cancelledAt):''}`;
    $('#outcome').textContent=outcomes[o.status]||'';$('#outcome').dataset.tone=tone(o.status);
    const accepted=(o.storeOrders||[]).flatMap(s=>s.lines).filter(l=>l.accepted).reduce((t,l)=>t+Number(l.subtotal),0);
    dl($('#order-summary'),[['Basket total',money(o.total,c)],...(o.storeOrders?.length?[['Accepted by stores',money(accepted,c)]]:[]),['Fulfilment',b.settings.fulfilment==='PICKUP'?'Pick-up':'Delivery']]);
    $('#mode-note').textContent=o.mode?'Simulated partner stores: no payment is taken and no real supermarket receives this order.':'Local confirmation only; this order was not sent to a store.';
    $('#stores').replaceChildren();
    for(const s of o.storeOrders||[]){const card=node('article');card.className='store-card';const head=node('div');head.className='section-heading';head.append(node('h3',s.store),chip(storeStates[s.status],tone(s.status)));
        card.append(head,node('p',s.fulfilmentDetail||''),node('p',`Store reference ${s.retailerReference||'pending'} · ${s.attempts} attempt(s) · fee ${s.fee==null?'not quoted':money(s.fee,c)}`));
        if(s.reason){const r=node('p',s.reason);r.className='field-error';card.append(r);}
        card.append(table(['SKU','Product','Packs','Subtotal','Result'],s.lines.map(l=>[l.sku,l.name,String(l.packages),money(l.subtotal,c),l.accepted?'Accepted':`Rejected${l.reason?': '+l.reason:''}`])));$('#stores').append(card);}
    if(!o.storeOrders?.length)$('#stores').append(node('p',o.status==='REOPTIMISING'?'No store order is open while the basket is re-optimised.':'No store submission is recorded for this order.'));
    for(const h of o.history||[]){const d=node('details');d.className='store-card';d.append(node('summary',`Submission ${h.number} · ${states[h.status]||h.status} · ${money(h.total,c)}`),table(['Store','Status','Rejected items'],h.storeOrders.map(s=>[s.store,storeStates[s.status],s.lines.filter(l=>!l.accepted).map(l=>l.sku).join(', ')||'—'])));$('#stores').append(d);}
    const signals=o.preferenceSignals||b.settings;
    dl($('#links'),[['Meal plan',anchor(`Plan v${o.planVersion??b.planVersion}`,'/plan.html?id='+(o.planId||b.planId))],['Market evidence',anchor(`Revision ${o.marketRevision??b.marketRevision}`,'/market-result.html?id='+(o.resultId||b.resultId))],['Basket',anchor('Open basket','/basket.html?id='+o.basketId)],
        ['Selected SKUs',(o.selectedSkus?.length?o.selectedSkus:b.evaluation.lines.map(l=>l.product.facts.sku)).join(', ')],
        ['Preference signals',`Budget ${money(signals.budget,c)} · ${String(signals.preference).toLowerCase()} · ${signals.fulfilment==='PICKUP'?'pick-up':'delivery'}${signals.preferredStores.length?' · stores '+signals.preferredStores.join(', '):''}${(signals.exclusions||[]).length?' · excludes '+signals.exclusions.map(e=>e.value).join(', '):''}`],
        ['Promotion decision',o.promotionDecision||b.evaluation.promotionDecision||'No promotion affected the selection.'],['Price-quality decision',o.qualityDecision||b.evaluation.qualityDecision||'Lowest-cost selection.']]);
    lines($('#lines'),b.evaluation.lines,b.materialChanges||[]);
    $('#history').replaceChildren(table(['Time','Action','Details'],[...(o.events||[]),...b.audit.filter(e=>e.action.startsWith('ORDER'))].sort((x,y)=>String(x.at).localeCompare(String(y.at))).map(e=>[time(e.at),e.action,e.detail])));controls();}
const action=kind=>{const key=`nutrimarket.order.${id}.${kind}.${current.submission}.${current.storeOrders.map(s=>s.attempts).join('-')}`;let value=sessionStorage.getItem(key);if(!value){value=newRecordId();sessionStorage.setItem(key,value);}return {requestId:value};};
$('#recover').onclick=()=>work(async()=>render(await api(path+'/recover',{method:'POST',body:action('recover')})));
$('#reoptimise').onclick=()=>work(async()=>{const b=await api(path+'/reoptimise',{method:'POST',body:action('reoptimise')});location.assign('/basket.html?id='+b.basketId);});
const cancel=()=>work(async()=>{if(!confirm('Cancel this order? Every store order will be cancelled.'))return;render(await api(path+'/cancel',{method:'POST'}));});
$('#cancel').onclick=cancel;$('#cancel-failed').onclick=cancel;
try{await requireUser();if(!uuid(id))throw new Error('Open a valid order from your order history.');await work(async()=>{maxAttempts=(await api(base+'/access')).maxStoreAttempts||3;render(await api(path));});}catch(e){$('#message').textContent=e.message;}
