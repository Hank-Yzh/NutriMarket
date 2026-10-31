import {api} from './api.js';
import {requireUser,node,time,number,table} from './dietary-ui.js';
import {$,base,runner} from './procurement-ui.js';
const work=runner();
const grams=value=>`${number(value)} g`;
function stock(items){
    if(!items.length){$('#stock').replaceChildren(node('p','Your pantry is empty. Shelf-stable leftovers appear here after an order is accepted.'));return;}
    const t=table(['Product','Left (as sold)','Ready-to-eat equivalent','From order','Update'],[]);const body=t.querySelector('tbody');
    for(const i of items){
        const row=node('tr'),name=node('td');name.append(node('strong',i.name),node('div',`${i.store} · ${i.sku}${i.adjusted?' · corrected by you':''}`));
        const amount=node('input');amount.type='number';amount.min='0';amount.max='100000';amount.step='1';amount.value=Math.round(Number(i.remainingGrams));amount.setAttribute('aria-label',`Grams of ${i.name} left`);
        const save=node('button','Save');save.type='button';save.onclick=()=>work(async()=>render(await api(base+'/pantry/corrections',{method:'POST',body:{key:i.key,remainingGrams:Number(amount.value)}})));
        const empty=node('button','Used up');empty.type='button';empty.className='text-button';empty.onclick=()=>work(async()=>render(await api(base+'/pantry/corrections',{method:'POST',body:{key:i.key,remainingGrams:0}})));
        const actions=node('td');actions.className='pantry-actions';actions.append(amount,save,empty);
        row.append(name,node('td',grams(i.remainingGrams)),node('td',`≈ ${grams(i.edibleGrams)}`),node('td',time(i.addedAt)),actions);body.append(row);
    }
    $('#stock').replaceChildren(t);
}
function purchases(items){
    $('#purchases').replaceChildren(items.length?table(['Product','Store','Packs','Bought','Needed this week','Left over','Pantry'],items.map(p=>[p.name,p.store,number(p.packages),grams(p.purchasedGrams),grams(p.neededGrams),grams(p.leftoverGrams),
        Number(p.packages)===0?`Covered by pantry (${grams(p.fromPantryGrams)} ready-to-eat)`:p.keptInPantry?'Leftover kept':Number(p.fromPantryGrams)>0?`Also used ${grams(p.fromPantryGrams)} from pantry`:'Fresh, not tracked'])):node('p','No accepted orders in the last 7 days.'));
}
function render(v){stock(v.stock);purchases(v.lastWeek);}
$('#reload').onclick=()=>work(async()=>render(await api(base+'/pantry')));
try{await requireUser();await work(async()=>render(await api(base+'/pantry')));}catch(e){$('#message').textContent=e.message;}
