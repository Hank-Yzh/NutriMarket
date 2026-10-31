import {node,table,number,time,nutrients} from './dietary-ui.js';
export const $=selector=>document.querySelector(selector);
export const base='/api/v1/procurement';
export const states={DRAFT:'Draft basket',BLOCKED:'Needs attention',CHECKING:'Refreshing and revalidating',CHECKOUT_READY:'Waiting for your confirmation',STALE:'Refresh required',ORDERED:'Order placed',REVIEW_REQUIRED:'Awaiting expert review',TERMINATED:'Procurement terminated',
    SUBMITTING:'Submitting to stores',CONFIRMED:'Order confirmed',PARTIALLY_FULFILLED:'Partially fulfilled',REJECTED:'Rejected by the store',REOPTIMISING:'Re-optimising the basket',CANCELLED:'Order cancelled',
    LOCAL_CONFIRMED:'Local order confirmed',LOCAL_CANCELLED:'Local order cancelled'};
export const storeStates={PENDING:'Pending',ACCEPTED:'Accepted',PARTIAL:'Partially accepted',REJECTED:'Rejected',CANCELLED:'Cancelled'};
export const tiers={BASIC:'Basic range',STANDARD:'Standard range',PREMIUM:'Premium range'};
export const link=(label,url)=>{const a=node('a',label);a.href=url;return a;};
export const money=(amount,currency)=>amount==null?'—':`${currency} ${Number(amount).toLocaleString('en-AU',{minimumFractionDigits:2,maximumFractionDigits:2})}`;
export const chip=(text,tone='')=>{const c=node('span',text);c.className='chip';if(tone)c.dataset.tone=tone;return c;};
const grams=g=>Number(g)>=1000?`${number(Number(g)/1000)} kg`:`${number(g)} g`;
// Products plus one fulfilment fee per store order; unconfigured delivery fees stay visibly unquoted.
export const feeSummary=(e,currency)=>e.productTotal==null?'':[`Products ${money(e.productTotal,currency)}`,...(e.fees||[]).map(f=>`${f.store} ${f.fulfilment.toLowerCase()} ${f.quoted?money(f.amount,currency):'fee not quoted'}`)].join(' · ');
function fact(label,value){const d=node('div');d.append(node('span',label),node('strong',value));return d;}
// PM-03 marker: a planner-group ingredient substitution, or a product replaced since the basket the member last reviewed.
function substitution(l,changes){
    const f=l.product.facts,out=[];
    const planned=[...new Set(l.allocations.map(a=>a.plannedIngredientId).filter(x=>x&&x!==f.ingredientId))];
    if(planned.length)out.push(`Planned ${planned.join(', ')} → now ${f.ingredientId} (same substitution group)`);
    for(const c of changes.filter(c=>c.productId===l.product.productId&&['SUBSTITUTION','STOCK'].includes(c.type)))out.push(`Originally ${c.before} → now ${c.after} (same substitution group). ${c.detail}`);
    return out;
}
export function lines(container,items,changes=[],edit){
    container.replaceChildren();
    if(!items.length){container.append(node('p','No complete product selection is available. Resolve the issues above before checkout.'));return;}
    for(const l of items){
        const f=l.product.facts,box=node('article');box.className='product-match basket-line';
        const head=node('div');head.className='section-heading';const title=node('div');title.append(node('h3',f.name),node('p',`${f.brand} · ${f.category} · SKU ${f.sku}`));head.append(title,node('strong',money(l.subtotal,f.currency)));
        if(edit&&Number(l.packages)>0){const button=node('button','Edit');button.type='button';button.onclick=()=>edit(l.product.productId);head.append(button);}
        const badges=node('div');badges.className='chip-row';
        badges.append(chip(f.store),chip(f.availability==='IN_STOCK'?'In stock':f.availability.replaceAll('_',' ').toLowerCase(),f.availability==='IN_STOCK'?'good':'warn'));
        if(f.qualityTier)badges.append(chip(tiers[f.qualityTier],f.qualityTier==='PREMIUM'?'premium':''));
        if(f.promotion)badges.append(chip(`Promotion: ${f.promotion}`,'promo'));
        if(Number(l.pantryGrams)>0)badges.append(chip(Number(l.packages)===0?'Covered by your pantry · nothing to buy':`${grams(l.pantryGrams)} ready-to-eat from your pantry`,'good'));
        box.append(head,badges);
        for(const text of substitution(l,changes)){const p=node('p',text);p.className='substitution';box.append(p);}
        const facts=node('div');facts.className='fact-grid';
        facts.append(fact('Package size',grams(f.packageGrams)),fact('Unit price',`${money(Number(f.price)*1000/Number(f.packageGrams),f.currency)}/kg`),fact('Price per pack',money(f.price,f.currency)),
            fact('Quantity',`${l.packages} × ${grams(f.packageGrams)}`),fact('Needed as sold',grams(l.requiredSoldGrams)),fact('Surplus',grams(l.surplusGrams)),fact('Updated',time(f.observedAt)));
        box.append(facts);
        const checks=l.checks||[],evidence=node('details');evidence.className='check-details';
        evidence.append(node('summary',`Constraint evidence · ${checks.filter(c=>c.result==='PASS').length} checks passed`));
        const list=node('ul');list.className='check-list';
        for(const c of checks){const li=node('li');li.dataset.result=c.result;li.append(node('strong',c.constraint),node('span',c.evidence));list.append(li);}
        if(!checks.length)list.append(node('li',`Constraint check: ${l.allocations[0]?.evidence.hardConstraintStatus}. Detailed evidence is shown after the next rebuild.`));
        evidence.append(list);box.append(evidence);
        const meals=node('details');meals.append(node('summary',`Meals covered · ${l.allocations.length}`));
        meals.append(table(['Meal / requirement','Edible portion','As sold','Constraint check'],l.allocations.map(a=>[`${a.menuLineId} / ${a.requirementId}`,`${number(a.edibleGrams)} g`,`${number(a.soldGrams)} g`,a.evidence.hardConstraintStatus])));
        meals.append(node('p',`Ingredients: ${f.ingredients.join(', ')}. Allergens: ${f.allergens.join(', ')||'None declared in checked evidence'}. May contain: ${f.mayContain.join(', ')||'None declared in checked evidence'}.`),nutrients(f.per100g));
        box.append(meals);container.append(box);
    }
}
// Block A by menu line: one row per planned ingredient requirement.
export function mealTable(container,basket){
    container.replaceChildren();const plan=basket.source.originalPlanningPackage,byRequirement=new Map();
    for(const l of basket.evaluation.lines)for(const a of l.allocations)byRequirement.set(a.requirementId,{l,a});
    const meals=new Map(plan.weeklyMealPlan.map(m=>[m.menuLineId,m]));
    let previous;
    const rows=plan.ingredientRequirements.map(r=>{const m=meals.get(r.menuLineId),hit=byRequirement.get(r.requirementId),f=hit?.l.product.facts,first=r.menuLineId!==previous;previous=r.menuLineId;
        return [first?(m?`${m.date} · ${m.slot.toLowerCase()}`:r.menuLineId):'',first?m?.title||'':'',r.description,f?`${f.name} (${f.sku})`:'Not fulfilled',f?.store||'—',`${number(r.quantity)} g`,hit?(hit.a.evidence.hardConstraintStatus==='COMPLIANT'?'✓ Compliant':hit.a.evidence.hardConstraintStatus):'—'];});
    const t=table(['Day','Meal','Planned ingredient','Product','Store','Edible portion','Check'],rows);container.append(t);
}
// Hard constraints in a locked, read-only display (PM-07).
export function constraints(container,pack){
    container.replaceChildren();const c=pack.originalPlanningPackage.healthConstraintSet,targets=pack.originalPlanningPackage.nutritionTargets||{};
    const names={'1008':'Energy','1003':'Protein','1004':'Fat','1005':'Carbohydrate','1079':'Fibre','1093':'Sodium','1087':'Calcium','1089':'Iron'};
    const groups=[['Allergies',c.excludedAllergens],['Dietary restrictions',c.dietaryRestrictions],['Health conditions',c.relevantConditions],
        ['Daily nutrition limits',Object.entries(targets).map(([k,t])=>`${names[k]||k} ${number(t.minimum)}–${number(t.maximum)} ${t.unit}`)],
        ['Expert instructions',c.expertInstructions]];
    for(const [label,items] of groups){const box=node('div');box.className='locked-item';const value=node('div',items.length?items.join(', '):'None recorded');value.className='locked-value';value.setAttribute('aria-readonly','true');value.setAttribute('aria-disabled','true');box.append(node('span',`🔒 ${label}`),value);container.append(box);}
}
export function changeList(container,changes){
    container.replaceChildren();
    for(const c of changes){const li=node('li');li.dataset.type=c.type;const what=node('strong'),type=node('span',{PRICE:'Price',PACKAGE:'Package size',SUBSTITUTION:'Replacement',STOCK:'Out of stock, replaced',FEE:'Store order',TOTAL:'Total'}[c.type]||c.type),diff=node('span');type.className='change-type';what.append(type,document.createTextNode(c.subject));diff.className='diff';diff.append(node('del',c.before),document.createTextNode(' → '),node('ins',c.after));li.append(what,diff,node('small',c.detail));container.append(li);}
}
export function runner(after=()=>{}){let busy=false;return async fn=>{if(busy)return;busy=true;const controls=[...document.querySelectorAll('main button')];const disabled=controls.map(b=>b.disabled);controls.forEach(b=>b.disabled=true);$('#message').textContent='Working...';try{await fn();$('#message').textContent='';}catch(e){$('#message').textContent=e.message;}finally{busy=false;controls.forEach((b,i)=>b.disabled=disabled[i]);after();}};}
