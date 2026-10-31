import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const source=(await readFile(new URL('../../main/resources/static/basket.js',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'');
const id='00000000-0000-0000-0000-000000000001';
const settle=()=>new Promise(resolve=>setImmediate(resolve));
async function page(state='DRAFT',responses={}){
    const elements=new Map(),calls=[],storage=new Map(),redirects=[];
    function element(){return {value:'',checked:false,hidden:false,disabled:false,open:false,textContent:'',children:[],dataset:{},style:{},
        listeners:{},addEventListener(k,v){this.listeners[k]=v;},focus(){},scrollIntoView(){},
        append(...v){this.children.push(...v);},replaceChildren(...v){this.children=v;},setAttribute(k,v){this[k]=v;},replaceWith(){},closest(){return null;},showModal(){this.open=true;},close(){this.open=false;}};}
    const $=s=>{if(!elements.has(s))elements.set(s,element());return elements.get(s);};
    const radios={preference:['ECONOMY','BALANCED','PREMIUM'].map(value=>({value,checked:false})),fulfilment:['PICKUP','DELIVERY'].map(value=>({value,checked:false}))};
    const lines=[{product:{productId:id,facts:{name:'Rice',sku:'SKU-1',store:'Store A',ingredientId:'rice',qualityTier:'BASIC'}},packages:2,subtotal:27,allocations:[]}];
    let basket={basketId:id,version:2,state,planId:id,resultId:id,profileVersion:1,marketRevision:1,planVersion:1,materialChanges:[],learnedSignals:[],audit:[],
        settings:{budget:100,currency:'AUD',preference:'BALANCED',fulfilment:'PICKUP',preferredStores:[],excludedProductIds:[],exclusions:[]},
        source:{multiStoreComparable:true,marketIntelligenceResult:[],weeklyMealPlan:[{menuLineId:'d0-LUNCH',ingredients:[{ingredientId:'rice'}]}],originalPlanningPackage:{healthConstraintSet:{excludedAllergens:['Peanut'],dietaryRestrictions:[],relevantConditions:[]}}},
        evaluation:{issues:[],lines:state==='BLOCKED'?[]:lines,unfulfilledMenuLines:state==='BLOCKED'?['d0-LUNCH']:[],total:27,productTotal:27,fees:[],explanation:''}};
    const api=async(path,options={})=>{calls.push({path,...options});const kind=path.split('/').pop();
        if(responses[kind])return responses[kind](basket,options);
        if(kind==='compare')return {version:2,marketRevision:1,currency:'AUD',balancedMargin:.15,options:['PICKUP','DELIVERY'].flatMap(fulfilment=>['ECONOMY','BALANCED','PREMIUM'].map((preference,i)=>({preference,fulfilment,available:true,productTotal:27+i,feeTotal:fulfilment==='DELIVERY'?8:0,total:27+i+(fulfilment==='DELIVERY'?8:0),products:[{productId:id,name:'Rice',sku:'SKU-1',store:'Store A',packages:2,qualityTier:i?'PREMIUM':'BASIC',price:13.5,subtotal:27+i}],stores:[{store:'Store A',products:27+i,fee:fulfilment==='DELIVERY'?8:0,feeQuoted:true,total:27+i+(fulfilment==='DELIVERY'?8:0)}],issues:[],searchLimited:false})))};
        if(kind==='submit')return {basket:{...basket,version:3,state:'ORDERED',orderId:id},order:{orderId:id}};
        if(kind==='confirm')throw new Error('Response lost');
        if(kind==='decline'){basket={...basket,version:basket.version+1,state:'DRAFT'};return basket;}
        return basket;};
    await vm.runInNewContext('(async()=>{'+source+'})()',{$,api,base:'/api/v1/procurement',requireUser:async()=>({id}),node:(tag,text)=>Object.assign(element(),{tag,textContent:text||''}),table:()=>element(),time:()=>'',uuid:()=>true,
        states:{},lines(){},mealTable(){},constraints(){},changeList(container,changes){container.children=changes;},money:()=>'',runner:()=>async f=>{try{await f();}catch{}},
        URLSearchParams,location:{search:'?id='+id,assign:url=>redirects.push(url)},document:{querySelectorAll:selector=>{const found=selector.match(/^\[name="(preference|fulfilment)"\]$/);return found?radios[found[1]]:[];},createTextNode:t=>t},
        sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)},newRecordId:()=>crypto.randomUUID(),addressFields:()=>element(),fillAddress(){},readAddress:()=>null,sameAddress:()=>true,Math,Number,String,Map,Set,JSON});
    return {$,calls,redirects,basket:()=>basket,radios,editSelect:()=>$('#edit-products').children[0].children[1].children[0]};
}
const changed=(b)=>({basket:{...b,version:3,state:'CHECKOUT_READY',materialChanges:[{type:'PRICE',subject:'Rice',before:'AUD 3',after:'AUD 3.5',detail:'',productId:id}]},order:null});
test('submitting without a material change goes straight to the order result',async()=>{const p=await page();await p.$('#submit').onclick();
    assert.equal(p.calls.filter(c=>c.path.endsWith('/submit')).length,1);assert.equal(p.calls.filter(c=>c.path.endsWith('/confirm')).length,0);
    assert.deepEqual(p.redirects,['/order.html?id='+id]);assert.equal(p.$('#progress').hidden,true);});
test('material changes open the re-confirmation dialog without ordering',async()=>{const p=await page('DRAFT',{submit:changed});await p.$('#submit').onclick();
    assert.equal(p.$('#changes-dialog').open,true);assert.equal(p.$('#changes').children.length,1);assert.equal(p.redirects.length,0);assert.equal(p.calls.filter(c=>c.path.endsWith('/confirm')).length,0);});
test('uncertain re-confirmation retries reuse the same request ID and basket version',async()=>{const p=await page('CHECKOUT_READY');
    p.$('#confirm').onclick();await settle();p.$('#confirm').onclick();await settle();
    const calls=p.calls.filter(c=>c.path.endsWith('/confirm'));assert.equal(calls.length,2);assert.equal(calls[0].body.requestId,calls[1].body.requestId);
    assert.equal(calls[0].body.expectedVersion,2);assert.equal(calls[0].body.confirmOrder,true);assert.equal(p.redirects.length,0);});
test('cancelling the dialog declines the changes and places no order',async()=>{const p=await page('CHECKOUT_READY');p.$('#changes-dialog').open=true;await p.$('#decline').onclick();
    assert.equal(p.$('#changes-dialog').open,false);assert.equal(p.calls.filter(c=>c.path.endsWith('/decline')).length,1);assert.equal(p.basket().state,'DRAFT');assert.equal(p.redirects.length,0);});
test('ordered baskets cannot expose editing or another submission',async()=>{const p=await page('ORDERED');assert.equal(p.$('#preferences-fields').disabled,true);assert.equal(p.$('#submit-panel').hidden,true);assert.equal(p.$('#recovery-panel').hidden,true);});
test('preferences send exclusions and the standing automatic replanning consent',async()=>{const p=await page();p.$('#auto-replan').checked=true;
    p.$('#exclusion-type').value='BRAND';p.$('#exclusion-value').value='Value Basket';p.$('#add-exclusion').onclick();
    await p.$('#preferences-form').onsubmit({preventDefault(){}});await settle();const call=p.calls.find(c=>c.path.endsWith('/preferences'));
    assert.equal(call.body.settings.autoReplan,true);assert.deepEqual(JSON.parse(JSON.stringify(call.body.settings.exclusions)),[{type:'BRAND',value:'Value Basket',learned:false}]);assert.equal(call.body.settings.preference,'BALANCED');});
test('an exclusion naming a hard constraint is rejected with the reason',async()=>{const p=await page();p.$('#exclusion-type').value='BRAND';p.$('#exclusion-value').value='peanut';p.$('#add-exclusion').onclick();
    assert.equal(p.$('#exclusion-error').hidden,false);assert.match(p.$('#exclusion-error').textContent,/allergy/);
    await p.$('#preferences-form').onsubmit({preventDefault(){}});await settle();assert.equal(p.calls.find(c=>c.path.endsWith('/preferences')).body.settings.exclusions.length,0);});
test('replanning requires explicit AI consent before sending a request',async()=>{const p=await page('BLOCKED');await p.$('#replan').onclick();assert.equal(p.calls.filter(c=>c.path.endsWith('/replan')).length,0);});
test('product edits are staged in one editor and cancelling does not save them',async()=>{
    const p=await page();p.$('#edit-basket').onclick();await settle();assert.equal(p.$('#basket-editor').open,true);
    const select=p.editSelect();select.value='DISLIKE';select.onchange();
    assert.equal(p.calls.some(c=>c.method==='PUT'),false);p.$('#cancel-editor').onclick();
    assert.equal(p.$('#basket-editor').open,false);assert.equal(p.calls.some(c=>c.method==='PUT'),false);
    p.$('#edit-basket').onclick();await settle();assert.equal(p.editSelect().value,'KEEP');
});
test('saving not-needed removes the purchase scope in one recalculation request',async()=>{
    const p=await page('DRAFT',{preferences:(b,options)=>({...b,settings:{...options.body.settings,itemEdits:[{productId:id,choice:'NOT_NEEDED',ingredientIds:['rice']}]},evaluation:{...b.evaluation,lines:[],total:0}})});
    p.$('#edit-basket').onclick();await settle();const select=p.editSelect();select.value='NOT_NEEDED';select.onchange();
    await p.$('#preferences-form').onsubmit({preventDefault(){}});
    const saved=p.calls.find(c=>c.method==='PUT');assert.equal(saved.body.expectedVersion,2);
    assert.deepEqual(JSON.parse(JSON.stringify(saved.body.settings.itemEdits)),[{productId:id,choice:'NOT_NEEDED'}]);
    assert.equal(p.$('#basket-editor').open,false);assert.equal(p.$('#omitted-note').hidden,false);assert.match(p.$('#omitted-note').textContent,/rice/);
});
test('disliking a food asks for AI consent, then opens the revised plan from the meal planner',async()=>{
    const p=await page('DRAFT',{preferences:(b,options)=>({...b,version:4,state:'STALE',replanStatus:'REQUESTED',replanRequestId:'r-1',replanRequest:{reason:'PREFERENCE'},settings:options.body.settings})});
    p.$('#edit-basket').onclick();await settle();assert.equal(p.$('#food-consent-box').hidden,true);
    const select=p.editSelect();select.value='DISLIKE_FOOD';select.onchange();
    assert.equal(p.$('#food-consent-box').hidden,false);assert.equal(p.$('#food-consent').required,true);
    p.$('#food-consent').checked=true;await p.$('#preferences-form').onsubmit({preventDefault(){}});
    const saved=p.calls.find(c=>c.method==='PUT'&&c.path.endsWith('/preferences'));assert.equal(saved.body.aiConsent,true);
    assert.deepEqual(JSON.parse(JSON.stringify(saved.body.settings.itemEdits)),[{productId:id,choice:'DISLIKE_FOOD'}]);
    assert.deepEqual(p.redirects,['/plan.html?id='+id]);
});
test('option comparisons are previews and a chosen price-quality and fulfilment pair is saved together',async()=>{
    const p=await page();p.$('#edit-basket').onclick();await settle();
    assert.equal(p.calls.filter(c=>c.path.endsWith('/compare')).length,1);assert.equal(p.calls.some(c=>c.method==='PUT'),false);
    const premiumDelivery=p.$('#basket-options').children[1].children[1].children[2];premiumDelivery.onclick();
    await p.$('#preferences-form').onsubmit({preventDefault(){}});
    const saved=p.calls.find(c=>c.method==='PUT').body.settings;assert.equal(saved.preference,'PREMIUM');assert.equal(saved.fulfilment,'DELIVERY');
});
test('changing a product or budget clears a comparison calculated for older edits',async()=>{
    const p=await page();p.$('#edit-basket').onclick();await settle();assert.equal(p.$('#basket-options').children.length,2);
    p.$('#budget').value=200;p.$('#preferences-form').oninput({target:{name:'',id:'budget',closest:()=>null}});
    assert.equal(p.$('#basket-options').children.length,0);assert.match(p.$('#comparison-message').textContent,/Update the comparison/);
});
test('a rejected save retains the draft and keeps the editor open',async()=>{
    const p=await page('DRAFT',{preferences:()=>{throw new Error('Basket changed. Reload first.');}});
    p.$('#edit-basket').onclick();await settle();const select=p.editSelect();select.value='DISLIKE';select.onchange();
    await p.$('#preferences-form').onsubmit({preventDefault(){}});
    assert.equal(p.$('#basket-editor').open,true);assert.match(p.$('#editor-message').textContent,/Basket changed/);assert.equal(p.editSelect().value,'DISLIKE');
});
