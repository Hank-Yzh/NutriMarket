import {api} from './api.js';
import {requireUser,node,table,number,time,uuid} from './dietary-ui.js';
import {$} from './health-ui.js';
import {states,outcomes} from './market-ui.js';
import {newRecordId} from './upload-crypto.js';
// Read-only view of Agent 4's evidence. The only action is Refresh; Agent 5 makes every purchasing decision.
const id=new URLSearchParams(location.search).get('id'),path='/api/v1/market/results/'+encodeURIComponent(id);let current,busy=false,poll,selected;
try{selected=sessionStorage.getItem('market-ingredient:'+id)||undefined;}catch{/* Start with the first ingredient. */}
const money=(v,cur)=>v==null?'Not priced':`${cur} ${Number(v).toFixed(2)}`;
const grams=v=>{const g=Number(v);return g>=1000?`${number(g/1000)} kg`:`${Math.round(g).toLocaleString()} g`;};
const methods={QWEN_SEMANTIC:'Qwen semantic matching',VERIFIED_CATALOGUE_MAPPING:'Verified catalogue mapping'};
const tones={COMPLIANT:'good',NON_COMPLIANT:'bad',UNKNOWN:'warn'};
function chip(text,tone=''){const c=node('span',text);c.className='chip';if(tone)c.dataset.tone=tone;return c;}
function statusMessage(r){
    if(r.planStale)return 'Meal plan updated. Match the latest plan.';
    if(r.recoveryMessage)return r.recoveryMessage+(r.nextRecoveryAt?` Next retry: ${time(r.nextRecoveryAt)}.`:'');
    if(r.state==='MATCHING')return 'Matching products… this page updates by itself.';
    if(r.state==='STALE')return 'Product information is out of date. Select Refresh to match again.';
    if(r.state==='INTERRUPTED')return 'Matching paused. Select Refresh to retry.';
    return r.readyForProcurement?'Every ingredient has at least one eligible product. Your shopping basket is built from these offers.':'Some ingredients have no eligible product yet. See the details below; your shopping basket shows what happens next.';
}
const planned=(i,o)=>!o.ingredientId||o.ingredientId===i.ingredientId;
function substituteLabel(o){return (o.reasons||[]).find(r=>r.startsWith('Substitute from the meal plan'))?.replace('Substitute from the meal plan: ','').replace(/\.$/,'')||'';}
// Plain-language summary of the checks behind one offer; every part comes from recorded evidence, not model prose.
function evidence(o,i){
    const box=node('details');box.className='match-evidence';box.append(node('summary',`Evidence · ${o.name}`));
    const rows=[['Matched SKU',`${o.sku} · ${o.brand}`],['Match confidence',o.confidence==null?'Not recorded':`${number(Number(o.confidence)*100)}%`],['Matching method',methods[o.matchingMethod]||o.matchingMethod||'Not recorded'],
        ['Constraint status',o.hardConstraintStatus],['Availability',o.availability==='IN_STOCK'?'In stock':String(o.availability||'Unknown').replaceAll('_',' ').toLowerCase()],
        ['Dietary claims',(o.dietaryClaims||[]).join(', ')||'None recorded'],['Source',`${o.sourceId} · ${o.sourceReference}`],['Data timestamp',time(o.observedAt)]];
    if(o.promotion)rows.push(['Promotion',o.promotion]);
    box.append(table(['Evidence','Value'],rows));
    const swap=substituteLabel(o);
    box.append(node('p',o.eligible?`${methods[o.matchingMethod]||'Matching'} identified this as ${swap||i.name.toLowerCase()}; its ingredient list confirms the food, and the allergen, dietary restriction and nutrition-tolerance checks passed for every meal that uses it.`
        :`Not eligible: ${(o.reasons||[]).filter(r=>!r.startsWith('Substitute from')).join(' ')||outcomes[o.outcome]||'No eligible evidence.'}`));
    return box;
}
function offers(i,list){
    const sorted=[...list].sort((a,b)=>(Number(a.totalCost)||Infinity)-(Number(b.totalCost)||Infinity));
    const max=Math.max(...sorted.map(o=>Number(o.totalCost)||0),1),cheapest=sorted.find(o=>o.totalCost!=null);
    const t=table(['Store / product','Pack','Packs','Cost'],[]);const body=t.querySelector('tbody');
    for(const o of sorted){
        const row=node('tr'),product=node('td');product.append(node('strong',o.store),node('div',`${o.name} · ${o.brand}`));
        if(o===cheapest)product.append(chip('Lowest cost','good'));
        const cost=node('td',money(o.totalCost,o.currency));cost.append(node('div',o.unitPricePerKg==null?'':`${money(o.unitPricePerKg,o.currency)}/kg`));
        row.append(product,node('td',grams(o.packageGrams)),node('td',o.packages==null?'—':number(o.packages)),cost);body.append(row);
    }
    const bars=node('div');bars.className='cost-bars';bars.setAttribute('aria-hidden','true');
    for(const o of sorted)if(o.totalCost!=null){const row=node('div');row.className='cost-bar';const label=node('span',o.store),track=node('span'),value=node('span',money(o.totalCost,o.currency));
        track.className='cost-track';const fill=node('span');fill.className='cost-fill';if(o===cheapest)fill.dataset.best='true';fill.style.width=`${Math.max(4,Number(o.totalCost)/max*100)}%`;track.append(fill);row.append(label,track,value);bars.append(row);}
    const wrap=node('div');wrap.append(node('div'));wrap.firstChild.className='table-scroll';wrap.firstChild.append(t);if(bars.children.length>1)wrap.append(bars);
    for(const o of sorted)wrap.append(evidence(o,i));
    return wrap;
}
function rejected(i,list){
    if(!list.length)return null;
    const box=node('details');box.className='unmatched-candidates';box.append(node('summary',`Products checked but not eligible (${list.length})`));
    const scroll=node('div');scroll.className='table-scroll';
    scroll.append(table(['Product','Store','Status','Why not eligible'],list.map(o=>[`${o.name} (${o.sku})`,o.store,`${o.hardConstraintStatus} · ${outcomes[o.outcome]||o.outcome}`,(o.reasons||[]).filter(r=>!r.startsWith('Substitute from')).join(' ')||outcomes[o.outcome]||'—'])));
    box.append(scroll);return box;
}
function card(i,body,extra=[]){
    const a=node('article');a.className='market-ingredient';const head=node('div');head.className='section-heading';
    head.append(node('h3',i.name),node('span',`Needed: ${grams(i.requiredEdibleGrams)} edible · ${i.menuLines} meal${i.menuLines===1?'':'s'}`));
    a.append(head,...extra.filter(Boolean),body);return a;
}
function reasonsFor(i){
    const qs=current.requirements.filter(q=>q.ingredientId===i.ingredientId),counts=new Map();
    for(const q of qs)for(const c of q.candidates)for(const r of c.reasons||[])if(!r.startsWith('Substitute from'))counts.set(r,(counts.get(r)||0)+1);
    const notes=[...new Set(qs.flatMap(q=>(q.messages||[]).filter(m=>!m.startsWith('Source:'))))];
    return {top:[...counts].sort((a,b)=>b[1]-a[1]).slice(0,2).map(([r])=>r),notes};
}
function comparison(c){
    const ingredients=c?.ingredients||[];
    const groups={matched:[],substituted:[],unmatched:[]};
    for(const i of ingredients){const ok=i.options.filter(o=>o.eligible);
        if(ok.some(o=>planned(i,o)))groups.matched.push(i);else if(ok.length)groups.substituted.push(i);else groups.unmatched.push(i);}
    const stores=new Set(ingredients.flatMap(i=>i.options.filter(o=>o.eligible).map(o=>o.store)));
    $('#summary-chips').replaceChildren(chip(`${groups.matched.length} of ${ingredients.length} ingredients matched`,groups.matched.length===ingredients.length&&ingredients.length?'good':''),
        ...(groups.substituted.length?[chip(`${groups.substituted.length} via substitutes`,'warn')]:[]),...(groups.unmatched.length?[chip(`${groups.unmatched.length} not found`,'bad')]:[]),chip(`${stores.size} supermarket${stores.size===1?'':'s'} with offers`));
    $('#stores-panel').hidden=!c?.stores?.length;
    $('#store-totals').replaceChildren(table(['Supermarket','Ingredients it can supply','Cost of those ingredients','Not available here'],(c?.stores||[]).map(s=>[s.store,`${s.ingredientsCovered} of ${s.ingredientsRequired}`,money(s.weeklyTotal,s.currency),s.missingIngredients.join(', ')||'—'])));
    const kind=i=>groups.matched.includes(i)?'matched':groups.substituted.includes(i)?'substituted':'unmatched';
    // Problems first, then alphabetical, so the ingredients that need a look are at the top of the grid.
    const order={unmatched:0,substituted:1,matched:2};
    const sorted=[...ingredients].sort((a,b)=>order[kind(a)]-order[kind(b)]||a.name.localeCompare(b.name));
    if(!sorted.some(i=>i.ingredientId===selected))selected=sorted[0]?.ingredientId;
    $('#ingredients-panel').hidden=!sorted.length;
    $('#ingredient-grid').replaceChildren(...sorted.map(i=>{
        const k=kind(i),ok=i.options.filter(o=>o.eligible),best=ok.filter(o=>o.totalCost!=null).sort((a,b)=>a.totalCost-b.totalCost)[0];
        const tile=node('button');tile.type='button';tile.className='ingredient-tile';tile.dataset.kind=k;tile.setAttribute('aria-pressed',String(i.ingredientId===selected));
        const stores=new Set(ok.map(o=>o.store)).size;
        tile.append(node('strong',i.name),node('span',k==='unmatched'?'Not found':k==='substituted'?`Substitute · ${stores} store${stores===1?'':'s'}`:`${stores} store${stores===1?'':'s'}`),node('span',best?`From ${money(best.totalCost,best.currency)}`:'No price'));
        tile.onclick=()=>{selected=i.ingredientId;remember();comparison(c);$('#ingredient-detail').scrollIntoView({behavior:'smooth',block:'nearest'});};
        return tile;}));
    const i=sorted.find(x=>x.ingredientId===selected);
    $('#ingredient-detail').replaceChildren(...(i?[detail(i,kind(i))]:[]));
}
// The comparison for one ingredient: every eligible offer, or the substitutes, or why nothing was found.
function detail(i,k){
    const subs=substitutions(i,k==='substituted');
    if(k==='matched'){const note=node('p','Every eligible product from every supermarket. Costs are for whole packages; the lowest cost is not necessarily the best basket.');note.className='field-help';
        const same=i.options.filter(o=>o.eligible&&planned(i,o));
        return card(i,offers(i,same),[note,chip(`Same ingredient · ${same.length} product${same.length===1?'':'s'}`),rejected(i,i.options.filter(o=>!o.eligible&&planned(i,o))),subs]);}
    const r=reasonsFor(i);
    if(k==='substituted'){const why=node('p',`No eligible ${i.name.toLowerCase()}: ${r.top.join(' ')||'no product found.'}`);why.className='field-help';
        return card(i,subs||node('p','No planner-approved substitutions.'),[why,rejected(i,i.options.filter(o=>!o.eligible&&planned(i,o)))]);}
    const list=node('ul');list.className='health-list';
    for(const text of [...r.top,...r.notes])list.append(node('li',text));if(!list.children.length)list.append(node('li','No product was returned for this ingredient.'));
    const note=node('p','Neither the planned food nor any allowed substitute has an eligible product. Your shopping basket can ask for these meals to be replanned.');note.className='field-help';
    return card(i,rejected(i,i.options.filter(o=>planned(i,o)))||node('p','No candidate products were found.'),[note,list,subs]);
}
// Planner-approved substitutions come only from the meal plan's substitution groups: which foods, for which meals, in what amount.
// They change the ingredient itself, unlike the alternative products above. Each product is still checked on its own evidence.
function substitutions(i,open){
    const reqs=current.planning.ingredientRequirements.filter(r=>r.ingredientId===i.ingredientId),approved=new Map();
    for(const r of reqs){const g=current.planning.substitutionGroups.find(x=>x.substitutionGroupId===r.substitutionGroupId);
        for(const s of g?.substitutes||[]){const a=approved.get(s.ingredientId)||{grams:0,meals:0,checked:0,unchecked:0};a.grams+=Number(s.quantity);a.meals++;
            const q=current.requirements.find(x=>x.requirementId===r.requirementId);if(q?.checkedSubstitutes?.includes(s.ingredientId))a.checked++;else a.unchecked++;approved.set(s.ingredientId,a);}}
    if(!approved.size)return null;
    const box=node('details');box.className='substitutions';box.open=open;
    const eligible=i.options.filter(o=>!planned(i,o)&&o.eligible).length;
    box.append(node('summary',`Planner-approved Substitutions (${approved.size}) · changes the ingredient${eligible?` · ${eligible} eligible product${eligible===1?'':'s'}`:''}`));
    const help=node('p',`Your meal plan allows these foods instead of ${i.name.toLowerCase()} for the meals shown, each in its own amount. Every product is checked on its own allergens, dietary restrictions, nutrition tolerances and evidence. Your shopping basket uses them only when ${i.name.toLowerCase()} cannot be bought.`);help.className='field-help';box.append(help);
    for(const [ingredientId,a] of approved){
        const options=i.options.filter(o=>o.ingredientId===ingredientId).sort((x,y)=>Number(y.eligible)-Number(x.eligible)||(Number(x.totalCost)||Infinity)-(Number(y.totalCost)||Infinity));
        const name=substituteLabel(options[0]||{}).split(' instead of ')[0]||ingredientId.replaceAll('_',' ');
        const head=node('h4',`${name[0].toUpperCase()}${name.slice(1)} · ${grams(a.grams)} edible for ${a.meals} of ${reqs.length} meal${reqs.length===1?'':'s'}`);box.append(head);
        if(!a.checked){const p=node('p','Not checked against the catalogue yet. Refresh matching to check it.');p.className='field-help';box.append(p);continue;}
        if(!options.length){const p=node('p',`Checked${a.unchecked?' for some meals':''}: no product found in the catalogue.`);p.className='field-help';box.append(p);continue;}
        const t=table(['Matched SKU','Supermarket','Required quantity','Packs','Cost','Constraint status','Provenance'],[]);const body=t.querySelector('tbody');
        for(const o of options){const row=node('tr'),sku=node('td'),status=node('td');sku.append(node('strong',o.name),node('div',`${o.brand} · ${o.sku}`));
            status.append(chip(o.hardConstraintStatus,tones[o.hardConstraintStatus]));if(!o.eligible)status.append(node('div',(o.reasons||[]).filter(x=>!x.startsWith('Substitute from')).join(' ')||outcomes[o.outcome]||''));
            row.append(sku,node('td',o.store),node('td',grams(a.grams)),node('td',o.packages==null?'—':number(o.packages)),node('td',money(o.totalCost,o.currency)),status,node('td',`${o.sourceId} · ${time(o.observedAt)}`));body.append(row);}
        const scroll=node('div');scroll.className='table-scroll';scroll.append(t);box.append(scroll);
    }
    return box;
}
function remember(){try{sessionStorage.setItem('market-ingredient:'+id,selected);}catch{/* Selection simply resets without storage. */}}
function legend(){
    $('#legend').replaceChildren(table(['Status','Meaning','Can it go in your basket?'],[
        ['COMPLIANT','Evidence shows every applicable hard constraint is met.','Yes, it enters the candidate pool'],
        ['NON_COMPLIANT','A hard constraint is violated (allergen, restriction, ingredient list or nutrition tolerance).','No'],
        ['UNKNOWN','Evidence is missing, so the constraints cannot be confirmed.','No'],
        ['Out of stock','The product is not available to buy.','No'],
        ['No matching products','No product matching the ingredient was found.','No'],
        ['Low matching confidence','The match is not certain enough to be the same food and form.','Not automatically']]));
}
function render(r){
    current=r;const ready=r.state==='COMPLETED'&&r.readyForProcurement&&!r.planStale;
    $('#result-content').hidden=false;$('#period').textContent=`${r.planning.weekStart} – ${r.planning.weekEnd}`;
    $('#state').textContent=r.planStale?'Plan updated':r.state==='COMPLETED'&&!ready?'Partly matched':states[r.state];$('#state').dataset.state=ready?'COMPLETED':'';
    $('#readiness').textContent=statusMessage(r);$('#readiness').dataset.ready=String(ready);
    $('#meta').textContent=`Plan v${r.planVersion} · Market revision ${r.revision} · Profile v${r.profileVersion} · Re-matches performed ${r.refreshCount} · Consecutive service failures ${r.consecutiveRecoveryFailures||0}`;
    $('#capability').textContent=r.multiStoreComparable?'Offers from several supermarkets are comparable, so your basket may combine stores.':'Only one supermarket has comparable offers.';
    $('#plan-link').href='/plan.html?id='+encodeURIComponent(r.planId);
    comparison(r.state==='MATCHING'?null:r.priceComparison);
    $('#history').replaceChildren(table(['Time','Action','Details'],r.audit.map(e=>[time(e.at),e.action,e.detail])));
    clearTimeout(poll);if(!r.planStale&&(['MATCHING','STALE','INTERRUPTED'].includes(r.state)||r.nextRecoveryAt))poll=setTimeout(()=>load().catch(e=>{$('#message').textContent=e.message;}),5000);
}
async function load(){render(await api(path));}
// Opens the basket built from this result when there is one; otherwise the basket overview.
async function basketLink(){try{const d=await api('/api/v1/procurement/baskets?page=1&size=50');const b=d.items.find(x=>x.resultId===id);if(b)$('#basket-link').href='/basket.html?id='+encodeURIComponent(b.basketId);}catch{/* Keep the overview link. */}}
async function work(fn){if(busy)return;busy=true;$('#reload').disabled=true;$('#message').textContent='Loading market information...';try{await fn();$('#message').textContent='';}catch(e){$('#message').textContent=e.message;}finally{busy=false;$('#reload').disabled=false;}}
// Out-of-date or interrupted results are matched again for the whole week; otherwise Refresh only reloads.
async function rematch(){await api(path+'/refresh',{method:'POST',body:{expectedRevision:current.revision,menuLineIds:current.planning.weeklyMealPlan.map(m=>m.menuLineId),requestId:newRecordId()}});await load();}
$('#reload').onclick=()=>work(async()=>{await load();if(!current.planStale&&['STALE','INTERRUPTED','NEEDS_ATTENTION'].includes(current.state))await rematch();});
legend();
try{await requireUser();if(!uuid(id))throw new Error('Invalid market result ID. Open a result from the market overview.');await work(load);basketLink();}catch(e){$('#message').textContent=e.message;}
