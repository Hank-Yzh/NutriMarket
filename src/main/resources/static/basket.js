import {api} from './api.js';
import {requireUser,node,table,time,uuid} from './dietary-ui.js';
import {$,base,states,lines,mealTable,constraints,changeList,money,runner} from './procurement-ui.js';
import {newRecordId} from './upload-crypto.js';
import {addressFields,fillAddress,readAddress,sameAddress} from './address-ui.js';
const id=new URLSearchParams(location.search).get('id'),path=base+'/baskets/'+encodeURIComponent(id);let current,exclusions=[];
const frozen=['CHECKING','ORDERED','REVIEW_REQUIRED','TERMINATED'];
const replanning={REQUESTED:'Replanning the affected meals… meals that cannot be bought are being replaced. Publish the revised plan; it must pass market matching again before a new basket is built.',
    ESCALATED:'Replan limit reached. This basket was escalated to a Human Expert; no order can be placed until an expert decides.',
    FAILED:'Automatic replanning failed. See the basket history, then request replanning or expert review.'};
function controls(){if(!current)return;const editable=!frozen.includes(current.state);$('#preferences-fields').disabled=!editable;$('#edit-basket').disabled=!editable;$('#submit-panel').hidden=!editable;$('#order-bar').hidden=!editable;
    $('#submit').disabled=!current.evaluation.lines.length||current.evaluation.issues.length>0;
    // The order bar stays fixed in the viewport, so placing the order never needs a scroll to the bottom.
    $('#order-total').textContent=money(current.evaluation.total,current.settings.currency);$('#order-note').textContent=$('#submit').disabled?'Resolve the issues above to order':'Prices and stock are re-checked first';
    $('#recovery-panel').hidden=!editable||(!current.evaluation.issues.length&&!current.evaluation.unfulfilledMenuLines.length);$('#replan').disabled=!current.evaluation.unfulfilledMenuLines.length;}
const work=runner(controls);
const radio=name=>[...document.querySelectorAll(`[name="${name}"]`)].find(x=>x.checked)?.value;
const setRadio=(name,value)=>document.querySelectorAll(`[name="${name}"]`).forEach(x=>{x.checked=x.value===value;});
const dialog=()=>$('#changes-dialog');
function progress(on,text='Refreshing prices and stock and re-checking health constraints…'){$('#progress-text').textContent=text;$('#progress').hidden=!on;}
function summary(b){if(b.state==='DRAFT'&&!b.evaluation.lines.length&&!b.evaluation.issues.length)return 'Nothing to buy after your edits.';const n=b.materialChanges?.length||0;return {
    DRAFT:n?`Rebuilt with ${n} change(s); replacements are marked below. Review them, then confirm.`:'Review the draft basket. When you confirm, prices, stock and health checks are refreshed before anything is ordered.',
    CHECKOUT_READY:n?`${n} material change(s) since you reviewed this basket. Review and confirm them, or cancel to keep the basket without ordering.`:`Refreshed ${time(b.checkoutAt)} with no material change. Confirm within 5 minutes to place the order.`,
    STALE:'Evidence changed since this basket was built. Confirming refreshes it first, and you will see any material change before ordering.',
    BLOCKED:'This basket cannot be ordered yet. Resolve the issues below; constraints are never relaxed.',
    CHECKING:'Refreshing prices and stock and re-checking health constraints…',ORDERED:'An order was submitted for this basket. Open it for fulfilment details.',
    REVIEW_REQUIRED:'A Human Expert is reviewing this basket. Ordering is paused.',TERMINATED:'An expert ended procurement for this basket. No order can be placed.'}[b.state]||'';}
function lockedTerms(){const c=current.source.originalPlanningPackage.healthConstraintSet;return new Map([...c.excludedAllergens.map(x=>[x,'allergy']),...c.dietaryRestrictions.map(x=>[x,'dietary restriction']),...c.relevantConditions.map(x=>[x,'health condition'])].map(([k,v])=>[k.trim().toLowerCase(),v]));}
// The food a product is (its own ingredient), the meals in this plan that use it, and its display name.
const foodName=id=>current.source.originalPlanningPackage.nutritionSources?.[id]?.ingredient?.name||id.replace(/_/g,' ');
const plannedMeals=food=>current.source.weeklyMealPlan.filter(m=>m.ingredients.some(i=>i.ingredientId===food));
function productFood(productId){return [...current.evaluation.lines.map(l=>l.product),...current.source.marketIntelligenceResult.flatMap(q=>q.candidates).map(m=>m.product)].find(x=>x.productId===productId)?.facts.ingredientId;}
// Foods newly marked as disliked that this plan uses; changing those meals sends a replan request to Qwen, so it needs consent.
function newFoodDislikes(){const saved=new Set((current.settings.itemEdits||[]).filter(e=>e.choice==='DISLIKE_FOOD').flatMap(e=>e.ingredientIds||[]));
    return [...new Set([...itemEdits.values()].filter(e=>e.choice==='DISLIKE_FOOD').map(e=>productFood(e.productId)).filter(f=>f&&!saved.has(f)&&plannedMeals(f).length))];}
function showFoodConsent(){const on=newFoodDislikes().length>0;$('#food-consent-box').hidden=!on;$('#food-consent').required=on;if(!on)$('#food-consent').checked=false;}
function renderExclusions(){$('#exclusions').replaceChildren();
    exclusions.forEach((e,i)=>{const tag=node('span');tag.className='tag';if(e.learned)tag.dataset.learned='true';const remove=node('button','×');remove.type='button';remove.title=`Remove ${e.value}`;remove.onclick=()=>{exclusions.splice(i,1);renderExclusions();invalidateComparison();};tag.append(node('span',`${e.type==='BRAND'?'Brand':'SKU'}: ${e.value}${e.learned?' · learned':''}`),remove);$('#exclusions').append(tag);});
    if(!exclusions.length)$('#exclusions').append(node('p','No exclusions. Add a brand or SKU you do not want.'));}
function options(){const products=new Map(current.source.marketIntelligenceResult.flatMap(r=>r.candidates).map(m=>[m.product.productId,m.product.facts]));const type=$('#exclusion-type').value;
    const values=type==='BRAND'?[...new Set([...products.values()].map(f=>f.brand))].sort().map(v=>[v,v]):[...products.values()].map(f=>[f.sku,`${f.brand} ${f.name} · ${f.store}`]);
    $('#exclusion-options').replaceChildren(...values.map(([value,label])=>{const o=node('option',label);o.value=value;return o;}));}
function budget(b){const e=b.evaluation,c=b.settings.currency,limit=Number(b.settings.budget),total=Number(e.total);
    const quoted=(e.fees||[]).every(f=>f.quoted);
    const rows=[['Products',money(e.productTotal??0,c)],...(e.fees||[]).map(f=>[`${f.store} ${f.fulfilment==='PICKUP'?'pick-up':'delivery'}`,f.quoted?money(f.amount,c):'Not quoted']),['Basket total',quoted?money(e.total,c):'Not quoted'],['Budget',money(b.settings.budget,c)],[total>limit?'Over budget':'Remaining',quoted?money(Math.abs(limit-total),c):'Unconfirmed']];
    $('#budget-summary').replaceChildren(...rows.flatMap(([k,v])=>[node('dt',k),node('dd',v)]));
    $('#budget-fill').style.width=`${limit>0?Math.min(100,total/limit*100):0}%`;$('#budget-fill').dataset.over=String(total>limit);
    $('#budget-state').textContent=!quoted?'Total unavailable until the delivery fee is quoted.':!e.lines.length?'No products selected.':total>limit?'The basket exceeds your budget, so it cannot be ordered.':`${limit>0?Math.round(total/limit*100):0}% of your budget.`;
    const stores=[...new Set(b.source.marketIntelligenceResult.flatMap(r=>r.candidates).filter(m=>m.eligible).map(m=>m.product.facts.store))].sort();
    $('#store-note').textContent=b.settings.fulfilment==='DELIVERY'?'Delivery compares a complete order from one supermarket, including its delivery fee.':b.source.multiStoreComparable?'Pick-up can combine supermarkets to reduce the total. Collection fees are included; personal travel costs are not estimated.':'Pick-up is limited to one supermarket because comparable multi-store information is unavailable.';
    $('#store-note').dataset.limited=String(!b.source.multiStoreComparable);
    $('#decisions').textContent=[e.qualityDecision,e.promotionDecision].filter(Boolean).join(' ');
    const range=$('#budget-range');range.max=String(Math.max(300,Math.ceil(Math.max(limit,total*2)/50)*50));range.value=String(limit);}
let itemEdits=new Map(),comparison=null,comparisonGeneration=0,saving=false,savedAddress=null;
$('#address-fields').replaceWith(addressFields());
// The address only matters for home delivery; while hidden it is disabled, so its required fields never block a pick-up save.
function showAddress(){const box=$('#delivery-address'),on=radio('fulfilment')==='DELIVERY';box.hidden=!on;box.disabled=!on;}
const preferenceName=p=>({ECONOMY:'Economy',BALANCED:'Balanced',PREMIUM:'Premium'}[p]||p);
const modeName=m=>m==='DELIVERY'?'Delivery':'Pick-up';
const editor=()=>$('#basket-editor');
function invalidateComparison(){comparisonGeneration++;comparison=null;$('#basket-options').replaceChildren();$('#option-details').replaceChildren();$('#comparison-message').textContent='Update the comparison to see prices for these edits.';$('#compare-options').disabled=false;}
function draftSettings(){return {budget:Number($('#budget').value),currency:$('#currency').value,
    preference:radio('preference')||current.settings.preference,fulfilment:radio('fulfilment')||current.settings.fulfilment,
    preferredStores:[...document.querySelectorAll('[name="store"]:checked')].map(x=>x.value),excludedProductIds:current.settings.excludedProductIds||[],
    autoReplan:$('#auto-replan').checked,exclusions,itemEdits:[...itemEdits.values()].map(e=>({productId:e.productId,choice:e.choice}))};}
function renderEditProducts(){
    const products=new Map(current.evaluation.lines.filter(l=>Number(l.packages)>0).map(l=>[l.product.productId,{product:l.product,line:l}]));
    for(const edit of itemEdits.values())if(!products.has(edit.productId)){
        const product=current.source.marketIntelligenceResult.flatMap(q=>q.candidates).find(m=>m.product.productId===edit.productId)?.product;
        products.set(edit.productId,{product:product||{productId:edit.productId,facts:{name:'Previously removed product'}},line:null});
    }
    $('#edit-products').replaceChildren();
    for(const [id,{product,line}]of products){
        const f=product.facts,row=node('article');row.className='edit-product';row.dataset.productId=id;
        const heading=node('div');heading.append(node('strong',f.name||f.sku||id),node('p',[f.brand,f.store,f.sku].filter(Boolean).join(' · ')));
        if(line)heading.append(node('small',`${line.packages} pack(s) · ${money(line.subtotal,current.settings.currency)}`));
        const label=node('label','Your choice'),select=node('select');select.setAttribute('aria-label',`Action for ${f.name||f.sku||id}`);
        for(const [value,text]of [['KEEP','Keep'],['DISLIKE',"Don't like this product"],['DISLIKE_FOOD',"Don't like this food"],['NOT_NEEDED',"Don't need"]]){const o=node('option',text);o.value=value;select.append(o);}
        select.value=itemEdits.get(id)?.choice||'KEEP';const note=node('p');note.className='edit-product-note';
        const food=f.ingredientId,meals=food?plannedMeals(food).length:0,foodLabel=food?foodName(food):'This food';
        const explain=()=>{row.dataset.removed=String(select.value!=='KEEP');note.textContent=select.value==='DISLIKE'?'Another eligible product for the same food will be bought, and later baskets avoid this product.'
            :select.value==='DISLIKE_FOOD'?(meals?`${foodLabel} will be left out of your meal plans. Your meal planner replaces the ${meals} meal(s) that use it; publish the revised plan to get a new basket.`:`${foodLabel} is only bought here as a substitute. It will no longer be bought as one, and later meal plans leave it out.`)
            :select.value==='NOT_NEEDED'?'This ingredient will not be bought this time. No replacement will be added.':'Included when the basket is recalculated.';};
        select.onchange=()=>{if(select.value==='KEEP')itemEdits.delete(id);else itemEdits.set(id,{productId:id,choice:select.value});explain();showFoodConsent();invalidateComparison();};
        label.append(select);row.append(heading,label,note);explain();$('#edit-products').append(row);
    }
    if(!products.size)$('#edit-products').append(node('p','No selected products. Adjust exclusions or refresh product information to continue.'));
    showFoodConsent();
}
function openEditor(productId){
    if(!current||frozen.includes(current.state))return;
    fillSettings(current);$('#editor-message').textContent='';editor().showModal();
    if(productId){const row=[...$('#edit-products').children].find(r=>r.dataset.productId===productId);row?.scrollIntoView({block:'nearest'});row?.querySelector('select')?.focus();}
    compareOptions();
}
function closeEditor(){comparisonGeneration++;editor().close();fillSettings(current);$('#editor-message').textContent='';}
function packageCount(products){return products.reduce((n,p)=>n+Number(p.packages),0);}
function tierSummary(products){const counts={BASIC:0,STANDARD:0,PREMIUM:0,UNRATED:0};for(const p of products)counts[p.qualityTier||'UNRATED']+=Number(p.packages);return Object.entries(counts).filter(([,n])=>n).map(([tier,n])=>`${n} ${tier.toLowerCase()}`).join(' · ')||'No packages to buy';}
function selectionKey(o){return JSON.stringify([o.products.map(p=>[p.productId,Number(p.packages)]).sort(),o.stores.map(s=>[s.store,s.fee])]);}
function renderComparison(){
    if(!comparison)return;const currency=comparison.currency,selected=draftSettings();$('#basket-options').replaceChildren();
    const cheapest=comparison.options.filter(o=>o.available&&o.total!=null).reduce((best,o)=>best==null||Number(o.total)<Number(best.total)?o:best,null);
    for(const mode of ['PICKUP','DELIVERY']){
        const group=node('section');group.className='option-group';group.append(node('h4',mode==='PICKUP'?'Pick-up · may combine supermarkets':'Delivery · one supermarket'));
        const grid=node('div');grid.className='option-grid';const economy=comparison.options.find(o=>o.fulfilment===mode&&o.preference==='ECONOMY');
        for(const option of comparison.options.filter(o=>o.fulfilment===mode)){
            const button=node('button');button.type='button';button.className='basket-option';button.dataset.available=String(option.available);
            const chosen=option.preference===selected.preference&&option.fulfilment===selected.fulfilment;button.setAttribute('aria-pressed',String(chosen));
            button.append(node('span',preferenceName(option.preference)),node('strong',option.total==null?'Total not quoted':money(option.total,currency)));
            if(cheapest&&option.available&&Number(option.total)===Number(cheapest.total))button.append(node('small','Lowest compared total'));
            button.append(node('span',`Products ${money(option.productTotal,currency)} + fees ${option.feeTotal==null?'not quoted':money(option.feeTotal,currency)}`));
            button.append(node('span',option.stores.map(s=>s.store).join(' + ')||'No store order'),node('small',`${packageCount(option.products)} pack(s) · ${tierSummary(option.products)}`));
            const identical=economy&&selectionKey(economy)===selectionKey(option);
            button.append(node('small',!option.available?option.issues[0]||'Unavailable':option.preference==='ECONOMY'?'Lowest-cost selection for these quantities.':identical?'Same products and cost as Economy.':economy?.total!=null?`${money(Number(option.total)-Number(economy.total),currency)} more than Economy.`:'Different product selection.'));
            button.onclick=()=>{setRadio('preference',option.preference);setRadio('fulfilment',mode);renderComparison();};grid.append(button);
        }
        group.append(grid);$('#basket-options').append(group);
    }
    const option=comparison.options.find(o=>o.preference===selected.preference&&o.fulfilment===selected.fulfilment);$('#option-details').replaceChildren();if(!option)return;
    $('#option-details').append(node('h4',`${preferenceName(option.preference)} · ${modeName(option.fulfilment)} breakdown`));
    if(option.issues.length){const notice=node('p',option.issues.join(' '));notice.className='notice';$('#option-details').append(notice);}
    const scroll=node('div');scroll.className='table-scroll';scroll.append(table(['Supermarket','Products',modeName(option.fulfilment)+' fee','Store total'],option.stores.map(s=>[s.store,money(s.products,currency),s.feeQuoted?money(s.fee,currency):'Not quoted',money(s.total,currency)])));$('#option-details').append(scroll);
    const details=node('details');details.append(node('summary','Selected products and changes'));
    const old=new Set(current.evaluation.lines.filter(l=>Number(l.packages)>0).map(l=>l.product.productId));
    const productScroll=node('div');productScroll.className='table-scroll';productScroll.append(table(['Product','Supermarket','Range','Packs','Pack price','Subtotal'],option.products.map(p=>[`${old.has(p.productId)?'':'New · '}${p.name} (${p.sku})`,p.store,p.qualityTier?.toLowerCase()||'unrated',p.packages,money(p.price,currency),money(p.subtotal,currency)])));details.append(productScroll);
    const kept=new Set(option.products.map(p=>p.productId)),removed=current.evaluation.lines.filter(l=>Number(l.packages)>0&&!kept.has(l.product.productId)).map(l=>l.product.facts.name||l.product.facts.sku);
    if(removed.length)details.append(node('p','Removed from the saved selection: '+removed.join(', ')));$('#option-details').append(details);
}
async function compareOptions(){
    if(!editor().open)return;const generation=++comparisonGeneration;$('#compare-options').disabled=true;$('#comparison-message').textContent='Comparing products and supermarket fees…';$('#editor-message').textContent='';
    try{const result=await api(path+'/compare',{method:'POST',body:{expectedVersion:current.version,settings:draftSettings()}});if(generation!==comparisonGeneration||!editor().open)return;
        comparison=result;$('#comparison-message').textContent=`Balanced allows up to ${Number(result.balancedMargin)*100}% above Economy for higher recorded product ranges. ${result.options.some(o=>o.searchLimited)?'Compared using a bounded search.':''}`;renderComparison();
    }catch(e){if(generation===comparisonGeneration){comparison=null;$('#comparison-message').textContent=e.message;}}
    finally{if(generation===comparisonGeneration)$('#compare-options').disabled=false;}
}

function fillSettings(b){
    $('#budget').value=b.settings.budget;$('#currency').value=b.settings.currency;$('#currency-label').textContent=b.settings.currency;setRadio('preference',b.settings.preference==='LOWEST_COST'||b.settings.preference==='FEWER_STORES'?'ECONOMY':b.settings.preference);setRadio('fulfilment',b.settings.fulfilment);$('#auto-replan').checked=!!b.settings.autoReplan;
    exclusions=(b.settings.exclusions||[]).map(e=>({type:e.type,value:e.value,learned:!!e.learned}));renderExclusions();$('#exclusion-error').hidden=true;options();
    const stores=[...new Set(b.source.marketIntelligenceResult.flatMap(r=>r.candidates).map(m=>m.product.facts.store))].sort();$('#stores').replaceChildren();
    for(const store of stores){const label=node('label'),input=node('input');input.type='checkbox';input.name='store';input.value=store;input.checked=b.settings.preferredStores.includes(store);label.append(input,document.createTextNode(` ${store}`));$('#stores').append(label);}
    itemEdits=new Map((b.settings.itemEdits||[]).map(e=>[e.productId,{...e}]));renderEditProducts();invalidateComparison();
    fillAddress($('#delivery-address'),savedAddress);showAddress();
}
function issueItem(issue,b){
    const li=node('li');li.className='basket-issue';
    const overBudget=/\bexceeds your budget\b/i.test(issue);
    const title=overBudget?'Exceeds budget':/your exclusions.*remove every compliant product/i.test(issue)?'Exclusions block this meal':/no compliant candidate/i.test(issue)?'No suitable product':/no complete basket/i.test(issue)?'No complete basket':'Action required';
    const heading=node('div');heading.className='issue-heading';const icon=node('span','!');icon.className='issue-icon';icon.setAttribute('aria-hidden','true');
    heading.append(icon,node('strong',title));li.append(heading);
    if(overBudget){
        const c=b.settings.currency,limit=Number(b.settings.budget),total=Number(b.evaluation.total);
        const amounts=node('p',`Basket total ${money(total,c)} · Budget ${money(limit,c)} · Over by ${money(Math.max(0,total-limit),c)}`);amounts.className='issue-amounts';li.append(amounts);
    }
    li.append(node('p',issue));
    // No silent relaxation: show the lowest complete total for these meals so the member knows why the budget fails.
    if(overBudget&&b.minimumTotal!=null){const min=node('p',`The lowest complete basket for these meals costs ${money(b.minimumTotal,b.settings.currency)} (${b.minimumChoice}). Your budget is below that, so no complete basket fits it. Raise the budget to at least this amount, or mark items you don't need.`);min.className='issue-minimum';li.append(min);
        const use=node('a',`Use ${money(Math.ceil(Number(b.minimumTotal)),b.settings.currency)} as budget →`);use.href='#budget';use.className='issue-action';use.onclick=e=>{e.preventDefault();openEditor();$('#budget').value=String(Math.ceil(Number(b.minimumTotal)));$('#budget').dispatchEvent(new Event('input',{bubbles:true}));$('#budget').focus();};li.append(use);}
    if(overBudget){const action=node('a','Adjust budget →');action.href='#budget';action.className='issue-action';action.onclick=e=>{e.preventDefault();openEditor();$('#budget').focus();};li.append(action);}
    return li;
}
function render(b){current=b;$('#content').hidden=false;$('#state').textContent=states[b.state];$('#meta').textContent=`Basket v${b.version} · Market revision ${b.marketRevision} · Plan v${b.planVersion} · Profile v${b.profileVersion}`;
    $('#summary').textContent=summary(b);const replanText=b.replanStatus==='REQUESTED'&&b.replanRequest?.reason==='PREFERENCE'?"Your meal planner replaced the meals that use a food you don't like. Review and publish the revised plan; it must pass market matching again before a new basket is built.":replanning[b.replanStatus];
    $('#replan-status').hidden=!replanText||b.state==='ORDERED';$('#replan-status').textContent=replanText||'';$('#replan-status').dataset.status=b.replanStatus||'';
    $('#default-choice').hidden=!b.defaultChoice;$('#default-choice').textContent=b.defaultChoice?`Best complete basket chosen for you. ${b.defaultChoice}`:'';
    $('#learned').hidden=!b.learnedSignals?.length;$('#learned').textContent=b.learnedSignals?.length?`Defaults learned from earlier baskets (soft and editable, never hard constraints): ${b.learnedSignals.join('; ')}.`:'';
    const issues=b.evaluation.issues;$('#issues').hidden=!issues.length;$('#issues').replaceChildren(...issues.slice(0,4).map(x=>issueItem(x,b)));
    if(issues.length>4){const more=node('details'),li=node('li'),list=node('ul');li.className='basket-more-issues';list.className='basket-issues';list.append(...issues.slice(4).map(x=>issueItem(x,b)));more.append(node('summary',`Show ${issues.length-4} more issue(s)`),list);li.append(more);$('#issues').append(li);}$('#explanation').textContent=b.evaluation.explanation;
    $('#market-link').href='/market-result.html?id='+b.resultId;$('#plan-link').href='/plan.html?id='+b.planId;$('#order-link').hidden=!b.orderId;if(b.orderId)$('#order-link').href='/order.html?id='+b.orderId;
    constraints($('#constraints'),b.source);lines($('#lines'),b.evaluation.lines,b.materialChanges||[],frozen.includes(b.state)?null:openEditor,{compact:true});mealTable($('#meals'),b);
    fillSettings(b);
    budget(b);$('#submit').textContent=b.state==='CHECKOUT_READY'?'Review and confirm order →':'Confirm and place order →';
    renderSavedChoices(b);$('#history').replaceChildren(table(['Time','Action','Details'],b.audit.map(e=>[time(e.at),e.action,e.detail])));controls();}
function renderSavedChoices(b){
    const c=b.settings.currency,e=b.evaluation;$('#active-choices').replaceChildren(node('p',`${preferenceName(b.settings.preference)} · ${modeName(b.settings.fulfilment)}`),node('p',tierSummary(e.lines.filter(l=>Number(l.packages)>0).map(l=>({packages:l.packages,qualityTier:l.product.facts.qualityTier})))));
    const omitted=[...new Set((b.settings.itemEdits||[]).filter(x=>x.choice==='NOT_NEEDED').flatMap(x=>x.ingredientIds||[]))];
    const foods=[...new Set((b.settings.itemEdits||[]).filter(x=>x.choice==='DISLIKE_FOOD').flatMap(x=>x.ingredientIds||[]))].map(foodName);
    const notes=[omitted.length?`Not buying this time: ${omitted.join(', ')}. Your meal plan is unchanged.`:'',foods.length?`Foods you don't like: ${foods.join(', ')}. They are not bought as substitutes, and later meal plans leave them out.`:''].filter(Boolean);
    $('#omitted-note').hidden=!notes.length;$('#omitted-note').textContent=notes.join(' ');
    if(!e.lines.length&&!e.issues.length&&omitted.length)$('#lines').replaceChildren(node('p','No products to buy after these edits.'));
    $('#selected-store-summary').replaceChildren();for(const fee of e.fees||[]){const cost=e.lines.filter(l=>l.product.facts.store===fee.store).reduce((n,l)=>n+Number(l.subtotal),0);const row=node('div');row.className='selected-store';row.append(node('strong',fee.store),...(fee.address?[node('p',fee.address)]:[]),node('p',`${money(cost,c)} products + ${fee.quoted?money(fee.amount,c):'unquoted'} ${modeName(fee.fulfilment).toLowerCase()} fee${fee.quoted?' = '+money(cost+Number(fee.amount),c):''}`));$('#selected-store-summary').append(row);}
}
function review(b){const changes=b.materialChanges||[];
    $('#changes-title').textContent=changes.length?'Prices, stock or products changed':'Confirm the refreshed basket';
    $('#changes-intro').textContent=changes.length?`${changes.length} material change(s) since you reviewed this basket. Confirm to submit the refreshed basket, or cancel to return without ordering.`:`Refreshed ${time(b.checkoutAt)}. Nothing material changed.`;
    changeList($('#changes'),changes);$('#changes-total').textContent=`Total to submit: ${money(b.evaluation.total,b.settings.currency)}. The confirmation expires 5 minutes after the refresh.`;dialog().showModal();}
async function load(){const [b,a]=await Promise.all([api(path),api('/api/v1/procurement/address')]);savedAddress=a.address;render(b);}
// Each action keeps one request ID per basket version, so a retried click is applied only once (PM-08).
function request(kind,body){const key=`nutrimarket.procurement.${id}.${kind}`,signature=JSON.stringify(body);let stored;try{stored=JSON.parse(sessionStorage.getItem(key));}catch{}if(stored?.signature!==signature){stored={signature,id:newRecordId()};sessionStorage.setItem(key,JSON.stringify(stored));}return {...body,requestId:stored.id};}
$('#reload').onclick=()=>work(load);
$('#view-products').onclick=()=>{$('#lines').hidden=false;$('#meals').hidden=true;$('#view-products').setAttribute('aria-pressed','true');$('#view-meals').setAttribute('aria-pressed','false');};
$('#view-meals').onclick=()=>{$('#lines').hidden=true;$('#meals').hidden=false;$('#view-products').setAttribute('aria-pressed','false');$('#view-meals').setAttribute('aria-pressed','true');};
$('#budget-range').oninput=()=>{$('#budget').value=$('#budget-range').value;};$('#budget').oninput=()=>{$('#budget-range').value=$('#budget').value;};
$('#exclusion-type').onchange=()=>options();
$('#add-exclusion').onclick=()=>{const value=$('#exclusion-value').value.trim(),type=$('#exclusion-type').value;if(!value)return;
    const hard=lockedTerms().get(value.toLowerCase());if(hard){$('#exclusion-error').textContent=`"${value}" is a ${hard} from your health profile. It is always enforced and cannot be added, removed or waived with basket exclusions.`;$('#exclusion-error').hidden=false;return;}
    if(!exclusions.some(e=>e.type===type&&e.value.toLowerCase()===value.toLowerCase()))exclusions.push({type,value,learned:false});$('#exclusion-value').value='';$('#exclusion-error').hidden=true;renderExclusions();invalidateComparison();};
$('#edit-basket').onclick=()=>openEditor();
$('#close-editor').onclick=closeEditor;$('#cancel-editor').onclick=closeEditor;
editor().addEventListener('cancel',e=>{if(saving){e.preventDefault();return;}comparisonGeneration++;fillSettings(current);});
$('#compare-options').onclick=compareOptions;
$('#preferences-form').oninput=e=>{if(e.target.closest('#delivery-address')||e.target.id==='food-consent')return;if(['preference','fulfilment'].includes(e.target.name))renderComparison();else invalidateComparison();};
$('#preferences-form').onchange=e=>{if(e.target.closest('#delivery-address')||e.target.id==='food-consent')return;if(e.target.name==='fulfilment')showAddress();if(['preference','fulfilment'].includes(e.target.name))renderComparison();else invalidateComparison();};
$('#preferences-form').onsubmit=e=>{e.preventDefault();return work(async()=>{
    const foods=newFoodDislikes(),before=current.replanRequestId;
    saving=true;$('#preferences-fields').disabled=true;$('#editor-message').textContent=foods.length?`Saving, then asking your meal planner to replace the meals that use ${foods.map(foodName).join(', ')}… this can take a minute or two.`:current.state==='STALE'?'Product information changed since this basket was built. Re-checking prices and stock, then recalculating…':'Saving and recalculating…';comparisonGeneration++;
    try{const address=radio('fulfilment')==='DELIVERY'?readAddress($('#delivery-address')):null;
        if(address&&!sameAddress(address,savedAddress))savedAddress=(await api('/api/v1/procurement/address',{method:'PUT',body:address})).address;
        const b=await api(path+'/preferences',{method:'PUT',body:{expectedVersion:current.version,settings:draftSettings(),aiConsent:$('#food-consent').checked}});editor().close();
        if(b.replanRequestId&&b.replanRequestId!==before&&b.replanRequest?.reason==='PREFERENCE'&&b.replanStatus==='REQUESTED'){location.assign('/plan.html?id='+b.planId);return;}
        render(b);$('#editor-message').textContent='';
        const last=b.audit[b.audit.length-1];if(['FOOD_DISLIKE_SAVED','PREFERENCE_REPLAN_FAILED'].includes(last?.action))$('#message').textContent=last.detail;}
    catch(err){$('#editor-message').textContent=err.message;if(err.code==='EXCLUSION_REJECTED'){$('#exclusion-error').textContent=err.message;$('#exclusion-error').hidden=false;}throw err;}
    finally{saving=false;$('#preferences-fields').disabled=frozen.includes(current.state);}
});};
// Screen 2 → screen 3 or 4: refresh and revalidate, then the server orders directly unless something material changed.
$('#submit').onclick=()=>work(async()=>{if(current.state==='CHECKOUT_READY'){review(current);return;}
    progress(true);let result;try{result=await api(path+'/submit',{method:'POST',body:request('submit',{expectedVersion:current.version})});}finally{progress(false);}
    if(result.order){location.assign('/order.html?id='+result.order.orderId);return;}
    render(result.basket);if(result.basket.state==='CHECKOUT_READY')review(result.basket);});
$('#checkout').onclick=()=>work(async()=>{progress(true);try{render(await api(path+'/checkout',{method:'POST',body:request('checkout',{expectedVersion:current.version})}));}finally{progress(false);}});
$('#confirm').onclick=()=>work(async()=>{const o=await api(path+'/confirm',{method:'POST',body:request('confirm',{expectedVersion:current.version,confirmOrder:true})});dialog().close();location.assign('/order.html?id='+o.orderId);});
$('#decline').onclick=()=>work(async()=>{dialog().close();if(current.state==='CHECKOUT_READY')render(await api(path+'/decline',{method:'POST',body:{expectedVersion:current.version}}));});
$('#replan').onclick=()=>work(async()=>{if(!$('#ai-consent').checked)throw new Error('Consent to AI replanning before continuing.');const p=await api(path+'/replan',{method:'POST',body:request('replan',{expectedVersion:current.version,aiConsent:true})});location.assign('/plan.html?id='+p.planId);});
$('#review').onclick=()=>work(async()=>render(await api(path+'/review-request',{method:'POST',body:{expectedVersion:current.version}})));
try{await requireUser();if(!uuid(id))throw new Error('Open a valid basket from the overview.');await work(load);}catch(e){$('#message').textContent=e.message;}
