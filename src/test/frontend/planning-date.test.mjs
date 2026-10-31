import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source=(await readFile(new URL('../../main/resources/static/planning.js',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'');
const assessmentId='00000000-0000-0000-0000-000000000001',planId='00000000-0000-0000-0000-000000000002';
async function page({now='2026-10-28T04:00:00Z',timezone='Australia/Sydney'}={}) {
    class FixedDate extends Date {constructor(...args){super(...(args.length?args:[now]));}}
    const elements=new Map(),calls=[],redirects=[];
    function node(tag='',text='') {return {tag,textContent:text,value:'',options:[],children:[],listeners:{},checked:false,
        addEventListener(type,fn){this.listeners[type]=fn;},replaceChildren(...children){this.children=children;},
        append(child){this.children.push(child);if(child.tag==='option')this.options.push(child);}};}
    for(const id of ['plan-list','page-info','previous','next','generate','message','assessment','assessment-help',
        'more-assessments','generate-form','week-start','ai-consent','refresh','expert-link'])elements.set('#'+id,node());
    elements.get('#assessment').options.push(node('option'));
    const api=async(path,options={})=>{
        calls.push({path,...options});
        if(path.endsWith('/expert/access'))return {expert:false};
        if(path.endsWith('/profile'))return {information:{timezone}};
        if(path.startsWith('/api/v1/health/assessments'))return {items:[{assessmentId,planningClearance:'CLEARED',periodStart:'2026-10-21',periodEnd:'2026-10-27',profileVersion:1}]};
        if(options.method==='POST')return {planId};
        return {items:[]};
    };
    const get=id=>elements.get('#'+id);
    await vm.runInNewContext('(async()=>{'+source+'})()',{api,requireUser:async()=>({id:'fixture'}),node,card:node,
        uuid:()=>false,$:selector=>elements.get(selector),location:{search:'',assign:url=>redirects.push(url)},Date:FixedDate,Intl,URLSearchParams});
    return {get,calls,redirects,submit:()=>get('generate-form').listeners.submit({preventDefault(){}})};
}

test('meal plans default to tomorrow, allowing any start date from today through twelve weeks',async()=>{
    const p=await page();assert.equal(p.get('week-start').value,'2026-10-29');
    assert.equal(p.get('week-start').min,'2026-10-28');assert.equal(p.get('week-start').max,'2027-01-20');
});
test('tomorrow follows the health profile timezone at midnight and at year boundaries',async()=>{
    const sydney=await page({now:'2026-10-28T14:30:00Z'});assert.equal(sydney.get('week-start').value,'2026-10-30');
    const west=await page({now:'2026-10-28T14:30:00Z',timezone:'America/Los_Angeles'});assert.equal(west.get('week-start').value,'2026-10-29');
    const yearEnd=await page({now:'2026-12-31T04:00:00Z'});assert.equal(yearEnd.get('week-start').value,'2027-01-01');
});
test('calendar arithmetic keeps tomorrow correct through a daylight-saving transition',async()=>{
    const before=await page({now:'2026-10-03T13:30:00Z'});assert.equal(before.get('week-start').value,'2026-10-04');
    const afterMidnight=await page({now:'2026-10-03T14:30:00Z'});assert.equal(afterMidnight.get('week-start').value,'2026-10-05');
});
test('submission sends tomorrow or the selected date without moving it to a Monday',async()=>{
    const p=await page();p.get('assessment').value=assessmentId;p.get('ai-consent').checked=true;await p.submit();
    const post=p.calls.find(c=>c.method==='POST');assert.equal(post.body.weekStart,'2026-10-29');
    assert.equal(post.body.assessmentId,assessmentId);assert.equal(post.body.aiConsent,true);
    assert.equal(p.redirects[0],'/plan.html?id='+planId);
    const chosen=await page();chosen.get('assessment').value=assessmentId;chosen.get('ai-consent').checked=true;
    chosen.get('week-start').value='2026-10-31';await chosen.submit();
    assert.equal(chosen.calls.find(c=>c.method==='POST').body.weekStart,'2026-10-31');
});
