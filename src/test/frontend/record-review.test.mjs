import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source=(await readFile(new URL('../../main/resources/static/record.js',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'');
const id='00000000-0000-0000-0000-000000000001';
const flush=()=>new Promise(resolve=>setImmediate(resolve));
class Element {
    constructor(tag='div'){this.tag=tag;this.children=[];this.listeners={};this.dataset={};this.hidden=false;this.disabled=false;this.textContent='';this.classList={toggle(){}};}
    append(...children){for(const child of children){this.children.push(child);child.parent=this;}}
    replaceChildren(...children){this.children=[];this.append(...children);}
    addEventListener(type,listener){this.listeners[type]=listener;}
    querySelectorAll(selector){return this.children.flatMap(child=>[
        ...(selector==='input'&&child.tag==='input'||selector===`[name=${child.name}]`?[child]:[]),
        ...child.querySelectorAll(selector)
    ]);}
    querySelector(selector){return this.querySelectorAll(selector)[0];}
    remove(){this.parent.children=this.parent.children.filter(child=>child!==this);}
    focus(){this.focused=true;}
}
async function page({saveFails=false,pinned=true}={}) {
    const ids=['confirm','totals-note','nutrition-totals','confirmation-help','edit-rows','add-food','edit-foods','record-meta','record','status','status-help','metadata','editor','foods','downstream','replacement','nutrients','other-nutrients','additional-nutrients','delivery','edit-fields','message','edit-form','cancel-edit','refresh','retry','abandon','refresh-delivery'];
    const elements=new Map(ids.map(id=>['#'+id,new Element()])),calls=[];
    let record={recordId:id,status:'PENDING_MEMBER_CONFIRMATION',mealTime:'2026-10-28T01:00:00Z',
        estimates:[{name:'Cooked rice',searchTerm:'Rice, white, cooked',portionGrams:150,confidence:.7,fdcId:pinned?123:null}],
        foodItems:[{fdcId:123}],nutrients:{'1008':{amount:195,unit:'kcal'}},failures:[{message:'Old resolved error'}]};
    const actions=r=>({confirm:r.status==='PENDING_MEMBER_CONFIRMATION',edit:['PENDING_MEMBER_CONFIRMATION','DEFERRED'].includes(r.status)&&r.estimates.length>0,
        retry:r.status==='DEFERRED',abandon:['PENDING_MEMBER_CONFIRMATION','DEFERRED','REPLACEMENT_REQUIRED'].includes(r.status),validated:r.status==='VALIDATED'});
    const node=(tag,text)=>Object.assign(new Element(tag),{textContent:text??''});
    await vm.runInNewContext('(async()=>{'+source+'})()',{
        document:{querySelector:selector=>{if(selector==='#edit-rows input')return elements.get('#edit-rows').querySelector('input');assert.ok(elements.has(selector),`Unexpected element ${selector}`);return elements.get(selector);},querySelectorAll:()=>[]},
        URLSearchParams,location:{search:'?id='+id},window:{addEventListener(){}},confirm:()=>true,
        requireUser:async()=>({id:'member'}),uuid:()=>true,statuses:{PENDING_MEMBER_CONFIRMATION:'Awaiting confirmation',VALIDATED:'Confirmed'},
        statusHelp:{PENDING_MEMBER_CONFIRMATION:'Review foods',VALIDATED:'Confirmed'},actions,node,time:value=>value,number:String,confidence:value=>String(value),
        table:(headers,rows)=>Object.assign(new Element('table'),{rows}),nutrients:values=>Object.assign(new Element(),{values}),
        api:async(path,options={})=>{
            calls.push({path,...options});
            if(options.method==='PUT'){
                if(saveFails)throw new Error('Unable to recalculate.');
                record={...record,estimates:options.body.foods,nutrients:{'1008':{amount:260,unit:'kcal'}}};
            }
            if(path.endsWith('/confirm'))record={...record,status:'VALIDATED'};
            if(path.endsWith('/delivery'))return {state:'DELIVERED'};
            return structuredClone(record);
        }
    });
    return {calls,get:id=>elements.get('#'+id),
        edit(value,name='portionGrams'){if(elements.get('#editor').hidden)elements.get('#edit-foods').listeners.click();elements.get('#edit-rows').children[0].querySelector(`[name=${name}]`).value=value;elements.get('#edit-form').listeners.input();},
        async save(){elements.get('#edit-form').listeners.submit({preventDefault(){}});await flush();},
        async confirm(){elements.get('#confirm').listeners.click();await flush();}};
}
test('unsaved portions block confirmation until nutrition is recalculated',async()=>{
    const p=await page();assert.equal(p.get('confirm').disabled,false);
    p.edit('200');assert.equal(p.get('confirm').disabled,true);assert.match(p.get('totals-note').textContent,/unsaved/);
    await p.confirm();assert.equal(p.calls.some(c=>c.path.endsWith('/confirm')),false);
    await p.save();
    const food=p.calls.find(c=>c.method==='PUT').body.foods[0];
    assert.equal(food.portionGrams,200);assert.equal(food.fdcId,123);assert.equal(food.searchTerm,'Rice, white, cooked');
    assert.equal(p.get('confirm').disabled,false);assert.equal(p.get('nutrients').children[0].rows.find(row=>row[0]==='Energy')[1],'260 kcal');
    await p.confirm();assert.equal(p.get('confirm').hidden,true);assert.equal(p.get('editor').hidden,true);
    assert.equal(p.get('foods').hidden,false);assert.match(p.get('delivery').textContent,/Ready for health assessment/);
});
test('renaming a food discards its old nutrition match',async()=>{
    const p=await page();p.edit('Grilled chicken','name');await p.save();
    const food=p.calls.find(c=>c.method==='PUT').body.foods[0];
    assert.equal(food.searchTerm,'Grilled chicken');assert.equal(food.fdcId,null);assert.equal(food.confidence,0);
});
test('a portion edit does not promote an approximate source match to an explicitly pinned selection',async()=>{
    const p=await page({pinned:false});p.edit('200');await p.save();
    const food=p.calls.find(c=>c.method==='PUT').body.foods[0];
    assert.equal(food.fdcId,null);assert.equal(food.searchTerm,'Rice, white, cooked');
});
test('failed recalculation keeps confirmation blocked and the edits available',async()=>{
    const p=await page({saveFails:true});p.edit('200');await p.save();
    assert.equal(p.get('confirm').disabled,true);assert.match(p.get('message').textContent,/Unable to recalculate/);
    assert.equal(p.get('edit-rows').children[0].querySelector('[name=portionGrams]').value,'200');
});
test('discarding unsaved edits restores the saved portions and confirmation',async()=>{
    const p=await page();p.edit('200');p.get('cancel-edit').listeners.click();
    assert.equal(p.get('edit-rows').children[0].querySelector('[name=portionGrams]').value,150);
    assert.equal(p.get('confirm').disabled,false);assert.doesNotMatch(p.get('totals-note').textContent,/unsaved/);
});
