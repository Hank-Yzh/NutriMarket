import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source = (await readFile(new URL('../../main/resources/static/upload.js', import.meta.url), 'utf8')).replace(/^import .*;\n/gm, '');
const member = '00000000-0000-0000-0000-000000000001';
const oldId = '00000000-0000-0000-0000-000000000002';
const newId = '00000000-0000-0000-0000-000000000003';
const mealTime = '2026-10-12T12:00';
const storageKey = 'nutrimarket.upload.' + member;
const draft = () => ({id:oldId,version:3,mealTime,signature:JSON.stringify([member,3,new Date(mealTime).toISOString(),'photo-hash'])});
async function page({versions=[4],saved=null,recordExists=false,conflict=false,profileFailure=false,photoPresent=true,mealDescription=''}={}) {
    const selectors = new Map(),calls=[],redirects=[],storage=new Map(saved?[[storageKey,JSON.stringify(saved)]]:[]);
    const element = () => ({value:'',hidden:true,disabled:false,textContent:'',listeners:{},addEventListener(type,fn){this.listeners[type]=fn;}});
    for (const id of ['upload-form','upload-fields','message','photo','preview','meal-time','pending','pending-link','meal-description','remove-photo']) selectors.set('#'+id,element());
    const form=selectors.get('#upload-form');form.elements={mealTime:selectors.get('#meal-time'),mealDescription:selectors.get('#meal-description')};
    form.elements.mealDescription.value=mealDescription;
    const photo={size:10,type:'image/jpeg',arrayBuffer:async()=>new ArrayBuffer(1)};
    class FormData {
        constructor(input){this.values=new Map(input?[['mealTime',input.elements.mealTime.value],['photo',photoPresent?photo:null],['mealDescription',input.elements.mealDescription.value],['consent','on']]:[]);}
        get(key){return this.values.get(key);} set(key,value){this.values.set(key,value);}
    }
    let profileIndex=0;
    const api=async(path,options={})=>{
        calls.push({path,...options});
        if(path==='/api/v1/health/profile'){
            if(profileFailure)throw new Error('Profile service unavailable.');
            return {version:versions[Math.min(profileIndex++,versions.length-1)]};
        }
        if(options.method==='POST'){
            if(conflict)throw Object.assign(new Error('Profile changed.'),{status:409,code:'PROFILE_VERSION_CONFLICT'});
            return {recordId:options.body.get('recordId')};
        }
        if(recordExists)return {recordId:oldId};
        throw Object.assign(new Error('Record not found.'),{status:404});
    };
    await vm.runInNewContext('(async()=>{'+source+'})()',{
        document:{querySelector:selector=>selectors.get(selector)},FormData,Date,URL,
        sessionStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
        location:{assign:url=>redirects.push(url)},api,requireUser:async()=>({id:member}),
        detailUrl:id=>'/record.html?id='+id,uuid:value=>/^[0-9a-f-]{36}$/.test(value||''),photoDigest:async()=>'photo-hash',newRecordId:()=>newId,
        prepareMealPhoto:async file=>file,
        createImageBitmap:async()=>({width:800,height:800,close(){}})
    });
    return {form,calls,redirects,storage,selectors,submit:()=>form.listeners.submit({preventDefault(){}})};
}

test('restoring an old upload draft never replaces the current health profile version',async()=>{
    const p=await page({saved:draft()});
    assert.equal(p.form.elements.mealTime.value,mealTime);
    assert.equal(p.selectors.get('#pending').hidden,false);
    assert.match(p.selectors.get('#message').textContent,/health profile has changed/);
    await p.submit();
    assert.equal(p.calls.find(c=>c.method==='POST').body.get('profileVersion'),'4');
});
test('submission refreshes the profile version after the page was opened',async()=>{
    const p=await page({versions:[4,5]});await p.submit();
    const post=p.calls.find(c=>c.method==='POST');assert.equal(post.body.get('profileVersion'),'5');
    assert.equal(p.redirects[0],'/record.html?id='+newId);
});
test('a previously accepted upload under the older profile is opened without a duplicate submission',async()=>{
    const p=await page({saved:draft(),recordExists:true});await p.submit();
    assert.equal(p.calls.filter(c=>c.method==='POST').length,0);
    assert.equal(p.redirects[0],'/record.html?id='+oldId);
    assert.equal(p.storage.has(storageKey),false);
});
test('an unreceived old draft is submitted once under the current profile with a new record ID',async()=>{
    const p=await page({saved:draft()});await p.submit();
    const post=p.calls.find(c=>c.method==='POST');assert.equal(post.body.get('profileVersion'),'4');assert.equal(post.body.get('recordId'),newId);
});
test('a profile change racing the upload resynchronises the form and clears the rejected pending request',async()=>{
    const p=await page({versions:[3,3,4],conflict:true});await p.submit();
    assert.equal(p.storage.has(storageKey),false);
    assert.equal(p.selectors.get('#pending').hidden,true);assert.equal(p.calls.filter(c=>c.method==='POST').length,1);
    assert.match(p.selectors.get('#message').textContent,/details have been refreshed/);
});
test('failure to fetch the current profile keeps the upload form closed',async()=>{
    const p=await page({profileFailure:true});assert.equal(p.form.hidden,true);
    assert.match(p.selectors.get('#message').textContent,/Profile service unavailable/);
});
test('a meal description can be submitted without a photo',async()=>{
    const p=await page({photoPresent:false,mealDescription:'  米饭200克，鸡肉150克  '});await p.submit();
    const post=p.calls.find(c=>c.method==='POST');
    assert.equal(post.body.get('mealDescription'),'米饭200克，鸡肉150克');
    assert.equal(post.body.get('photo'),undefined);
    assert.equal(post.body.get('profileVersion'),'4');
    assert.equal(p.redirects[0],'/record.html?id='+newId);
});
test('a photo and its supplementary description are submitted together',async()=>{
    const p=await page({mealDescription:'Shared plate; I ate about one third.'});await p.submit();
    const post=p.calls.find(c=>c.method==='POST');
    assert.ok(post.body.get('photo'));
    assert.equal(post.body.get('mealDescription'),'Shared plate; I ate about one third.');
});
test('an empty meal is rejected before creating an upload request',async()=>{
    const p=await page({photoPresent:false});await p.submit();
    assert.equal(p.calls.filter(c=>c.method==='POST').length,0);
    assert.match(p.selectors.get('#message').textContent,/photo or describe/);
});
test('changing the description creates a new request instead of reusing an older upload',async()=>{
    const saved=draft();saved.mealDescription='Cooked rice';
    saved.signature=JSON.stringify([member,3,new Date(mealTime).toISOString(),'photo-hash','Cooked rice']);
    const p=await page({versions:[3],saved});
    p.form.elements.mealDescription.value='Grilled chicken';await p.submit();
    assert.equal(p.calls.find(c=>c.method==='POST').body.get('recordId'),newId);
});
test('an accepted upload is opened before resubmitting even when the profile has not changed',async()=>{
    const p=await page({versions:[3],saved:draft(),recordExists:true});await p.submit();
    assert.equal(p.calls.filter(c=>c.method==='POST').length,0);
    assert.equal(p.redirects[0],'/record.html?id='+oldId);
});
