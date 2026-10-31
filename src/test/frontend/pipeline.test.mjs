import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source=(await readFile(new URL('../../main/resources/static/procurement.js',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'');
const html=await readFile(new URL('../../main/resources/static/procurement.html',import.meta.url),'utf8');
const id='00000000-0000-0000-0000-000000000001';
async function page(stage,{retryFails=false}={}){
    const calls=[],redirects=[],timers=new Map(),elements=new Map();let nextTimer=0;
    const node=(tag,text='')=>({tag,textContent:text,children:[],hidden:false,disabled:false,
        append(...items){this.children.push(...items);},replaceChildren(...items){this.children=items;}});
    for(const [,id] of html.matchAll(/id="([^"]+)"/g))elements.set('#'+id,node('div'));
    const $=selector=>{assert.ok(elements.has(selector),selector);return elements.get(selector);};
    const status={stage,message:stage==='MATCH_PAUSED'?'Product information changed or expired. Matching will retry automatically.':stage==='BASKET_PAUSED'?'Your matches are available, but the basket could not be created. Try building it again.':'Products matched. Building your basket…',
        assessmentId:id,planId:id,resultId:id,planIssues:[],marketState:stage==='MATCH_PAUSED'?'STALE':'COMPLETED'};
    const api=async(path,options={})=>{
        calls.push({path,...options});
        if(path.endsWith('/access'))return {expert:false,reportStore:null};
        if(path.endsWith('/pipeline'))return status;
        if(options.method==='POST'){
            if(retryFails)throw new Error('Basket input is no longer current. Refresh matching.');
            return {basketId:id};
        }
        return {items:[]};
    };
    await vm.runInNewContext('(async()=>{'+source+'})()',{
        api,requireUser:async()=>({id}),node,time:String,$,base:'/api/v1/procurement',states:{},money:String,lines:()=>{},
        chip:(text,tone)=>Object.assign(node('span',text),{tone}),link:(text,href)=>Object.assign(node('a',text),{href}),
        runner:after=>async fn=>{try{await fn();$('#message').textContent='';}catch(e){$('#message').textContent=e.message;}finally{after();}},
        setTimeout:fn=>{const n=++nextTimer;timers.set(n,fn);return n;},clearTimeout:n=>timers.delete(n),
        location:{assign:url=>redirects.push(url)}
    });
    return {get:id=>$('#'+id),calls,redirects,timers};
}
test('completed matching advances the progress indicator to basket creation and continues polling',async()=>{
    const p=await page('BUILDING_BASKET');
    const chips=p.get('pipeline-steps').children.map(a=>a.children[0]||a);
    assert.equal(chips[2].textContent,'✓ Product matching');assert.equal(chips[2].tone,'good');
    assert.equal(chips[3].textContent,'Basket');assert.equal(chips[3].tone,'warn');
    assert.equal(p.timers.size,1);assert.equal(p.calls.some(c=>c.method==='POST'),false);
});
test('stale matching shows its reason and recovery link instead of an endless preparation message',async()=>{
    const p=await page('MATCH_PAUSED');
    assert.match(p.get('pipeline-message').children[0].textContent,/changed or expired/);
    const matching=p.get('pipeline-steps').children[2].children[0];assert.equal(matching.tone,'bad');
    assert.equal(p.get('pipeline-message').children[1].href,'/market-result.html?id='+id);
    assert.equal(p.calls.some(c=>c.method==='POST'),false);assert.equal(p.timers.size,1);
});
test('failed handoff permits an explicit basket retry without matching again or placing an order',async()=>{
    const p=await page('BASKET_PAUSED');
    const retry=p.get('pipeline-message').children.find(c=>c.tag==='button');assert.ok(retry);
    assert.equal(p.timers.size,0);await retry.onclick();
    const posts=p.calls.filter(c=>c.method==='POST');assert.equal(posts.length,1);
    assert.equal(posts[0].path,'/api/v1/procurement/baskets');assert.equal(posts[0].body.resultId,id);
    assert.deepEqual(p.redirects,['/basket.html?id='+id]);
});
test('an unsuccessful basket retry retains the recovery controls and reports the error',async()=>{
    const p=await page('BASKET_PAUSED',{retryFails:true});
    await p.get('pipeline-message').children.find(c=>c.tag==='button').onclick();
    assert.match(p.get('message').textContent,/Refresh matching/);assert.equal(p.redirects.length,0);
    assert.ok(p.get('pipeline-message').children.find(c=>c.tag==='button'));
});
