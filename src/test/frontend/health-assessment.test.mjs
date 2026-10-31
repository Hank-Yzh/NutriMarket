import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source=(await readFile(new URL('../../main/resources/static/health.js',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'');
const assessmentId='00000000-0000-0000-0000-000000000001';
class FixedDate extends Date {
    constructor(...args){super(...(args.length?args:['2026-10-20T14:30:00Z']));} // October 21 in Sydney, still October 20 in Los Angeles.
}
async function page({meals=18,observed=6,missing=3,timezone='Australia/Sydney',search='',coverageFails=false,postFails=false}={}) {
    const elements=new Map(),calls=[],redirects=[];
    const element=()=>({value:'',textContent:'',disabled:false,hidden:false,open:false,children:[],listeners:{},
        addEventListener(type,fn){this.listeners[type]=fn;},replaceChildren(...children){this.children=children;},append(child){this.children.push(child);},
        showModal(){this.open=true;},close(){this.open=false;}});
    for(const id of ['assessment-list','page-info','previous','next','generate','message','refresh','week-start','generate-form',
        'period-summary','coverage-dialog','coverage-title','coverage-summary','coverage-days','coverage-help','continue-assessment',
        'cancel-assessment','add-meals','expert-link','profile-summary'])elements.set('#'+id,element());
    const api=async(path,options={})=>{
        calls.push({path,...options});
        if(path.endsWith('/expert/access'))return {expert:false};
        if(path.endsWith('/profile'))return {version:1,information:{timezone,goals:'Track meals'}};
        if(path.includes('/assessment-coverage?')) {
            if(coverageFails)throw new Error('Unable to check coverage.');
            const start=new URL('https://fixture.test'+path).searchParams.get('periodStart');
            return {periodStart:start,periodEnd:'2026-10-20',intakeCount:meals,observedDays:observed,missingMeals:missing,
                mealsPerDay:3,incompleteDays:missing?['2026-10-16']:[]};
        }
        if(options.method==='POST'){
            if(postFails)throw new Error('Unable to generate assessment.');
            return {assessmentId};
        }
        return {items:[],page:1};
    };
    await vm.runInNewContext('(async()=>{'+source+'})()',{
        api,requireUser:async()=>({id:'fixture'}),node:()=>element(),summaryCard:()=>element(),
        $:selector=>elements.get(selector),location:{search,assign:url=>redirects.push(url)},Date:FixedDate,Intl,URLSearchParams
    });
    const get=id=>elements.get('#'+id);
    return {get,calls,redirects,submit:()=>get('generate-form').listeners.submit({preventDefault(){}}),
        click:id=>get(id).listeners.click(),posts:()=>calls.filter(c=>c.method==='POST')};
}

test('assessment defaults to the last seven complete local dates rather than the previous Monday',async()=>{
    const p=await page();
    assert.equal(p.get('week-start').value,'2026-10-14');assert.equal(p.get('week-start').max,'2026-10-14');
    assert.equal(p.get('week-start').min,'2024-10-21');assert.match(p.get('period-summary').textContent,/2026-10-14 – 2026-10-20/);
    const west=await page({timezone:'America/Los_Angeles'});assert.equal(west.get('week-start').value,'2026-10-13');
});
test('a valid arbitrary start date is retained for reassessment',async()=>{
    const p=await page({search:'?periodStart=2026-10-07'});assert.equal(p.get('week-start').value,'2026-10-07');
    p.get('week-start').value='2026-10-08';p.get('week-start').listeners.change();
    assert.match(p.get('period-summary').textContent,/2026-10-08 – 2026-10-14/);
});
test('complete records generate an assessment directly without a missing-meal prompt',async()=>{
    const p=await page({meals:21,observed:7,missing:0});await p.submit();
    assert.equal(p.get('coverage-dialog').open,false);assert.equal(p.posts().length,1);
    assert.equal(p.posts()[0].body.periodStart,'2026-10-14');assert.equal(p.posts()[0].body.allowIncomplete,false);
    assert.equal(p.redirects[0],'/assessment.html?id='+assessmentId);
});
test('missing records prompt before generation and the member can continue using the checked period',async()=>{
    const p=await page();await p.submit();
    assert.equal(p.get('coverage-dialog').open,true);assert.equal(p.posts().length,0);
    assert.match(p.get('coverage-summary').textContent,/18 validated meals across 6 of 7 days/);
    assert.equal(p.get('continue-assessment').hidden,false);
    p.get('week-start').value='2026-10-07'; // Confirmation must apply to the period that was actually checked.
    await p.click('continue-assessment');
    assert.equal(p.posts().length,1);assert.equal(p.posts()[0].body.periodStart,'2026-10-14');
    assert.equal(p.posts()[0].body.allowIncomplete,true);assert.equal(p.get('coverage-dialog').open,false);
});
test('adding missing meals or cancelling never generates an assessment',async()=>{
    const add=await page();await add.submit();add.click('add-meals');
    assert.equal(add.redirects[0],'/upload.html');assert.equal(add.posts().length,0);
    const cancel=await page();await cancel.submit();cancel.click('cancel-assessment');await cancel.click('continue-assessment');
    assert.equal(cancel.get('coverage-dialog').open,false);assert.equal(cancel.posts().length,0);
    const escape=await page();await escape.submit();escape.get('coverage-dialog').listeners.cancel();await escape.click('continue-assessment');
    assert.equal(escape.posts().length,0);
});
test('a period with no meals offers entry instead of fabricating zero intake',async()=>{
    const p=await page({meals:0,observed:0,missing:21});await p.submit();
    assert.equal(p.get('continue-assessment').hidden,true);assert.match(p.get('coverage-help').textContent,/at least one meal/);
    assert.equal(p.posts().length,0);
});
test('a coverage failure does not silently continue; generation failures allow a new attempt',async()=>{
    const p=await page({coverageFails:true});await p.submit();
    assert.equal(p.posts().length,0);assert.equal(p.get('coverage-dialog').open,false);
    assert.match(p.get('message').textContent,/Unable to check/);assert.equal(p.get('generate').disabled,false);
    const failed=await page({postFails:true});await failed.submit();await failed.click('continue-assessment');
    assert.match(failed.get('message').textContent,/Unable to generate/);assert.equal(failed.get('generate').disabled,false);
});
