import {api} from './api.js';
import {requireUser,node} from './dietary-ui.js';
import {$,lines,targetBasis,targetTable} from './health-ui.js';
import {bodyFields,fillBody,readBody} from './body-ui.js';
import {addressFields,fillAddress,readAddress} from './address-ui.js';
const form=$('#profile-form'),fields=$('#profile-fields');let version=0,busy=false,dirty=false;
$('#body-fields').replaceWith(bodyFields());
function fill(p) {
    version=p.version;$('#profile-version').textContent='Current version v'+version;const data=p.information;
    for(const key of ['timezone','goals','preferences']) form.elements[key].value=data[key];
    for(const key of ['allergies','restrictions','conditions']) form.elements[key].value=data[key].join('\n');
    form.elements.aiSummaryConsent.checked=data.aiSummaryConsent;form.elements.autoPlanningConsent.checked=!!data.autoPlanningConsent;
    for(const box of form.querySelectorAll('[name=mealStyles]')) box.checked=(data.mealStyles||[]).includes(box.value);
    fillBody(form,data.body);
    $('#targets-panel').hidden=false;$('#targets').replaceChildren(targetTable(data.targets));targetBasis($('#target-basis'),data.targetSource,data.targetRationale);
    dirty=false;
}
form.addEventListener('input',()=>{dirty=true;});
form.addEventListener('submit',async e=>{
    e.preventDefault();if(busy)return;const input=new FormData(form);
    const body={expectedVersion:version,aiSummaryConsent:!!input.get('aiSummaryConsent'),autoPlanningConsent:!!input.get('autoPlanningConsent'),mealStyles:input.getAll('mealStyles'),body:readBody(form)};
    for(const key of ['timezone','goals','preferences'])body[key]=String(input.get(key)).trim();
    for(const key of ['allergies','restrictions','conditions'])body[key]=lines(String(input.get(key)));
    busy=true;fields.disabled=true;$('#message').textContent=body.aiSummaryConsent?'Saving and generating your daily targets...':'Saving...';
    try{fill(await api('/api/v1/health/profile',{method:'PUT',body}));$('#message').textContent=`Saved health profile v${version}. Generate a new weekly assessment using this version.`;}
    catch(error){$('#message').textContent=error.message;}finally{busy=false;fields.disabled=false;}
});
$('#address-fields').replaceWith(addressFields());
$('#address-form').addEventListener('submit',async e=>{
    e.preventDefault();$('#address-fieldset').disabled=true;$('#address-message').textContent='Saving...';
    try{fillAddress($('#address-form'),(await api('/api/v1/procurement/address',{method:'PUT',body:readAddress($('#address-form'))})).address);$('#address-message').textContent='Delivery address saved.';}
    catch(error){$('#address-message').textContent=error.message;}finally{$('#address-fieldset').disabled=false;}
});
window.addEventListener('beforeunload',e=>{if(dirty){e.preventDefault();e.returnValue='';}});
// Disliked foods are an Agent 3 preference stored apart from the versioned profile, so editing them never invalidates an assessment.
const dislikes='/api/v1/planning/disliked-foods';let catalogue=[];
function renderDislikes(list){
    $('#dislikes').replaceChildren(...list.map(f=>{const tag=node('span');tag.className='tag';const remove=node('button','×');remove.type='button';remove.title=`Remove ${f.name}`;
        remove.onclick=()=>dislikeAction(()=>api(`${dislikes}/${encodeURIComponent(f.ingredientId)}`,{method:'DELETE'}),`${f.name} can be planned again.`);tag.append(node('span',f.name),remove);return tag;}));
    if(!list.length)$('#dislikes').append(node('p','No disliked foods.'));
    const chosen=new Set(list.map(f=>f.ingredientId));
    $('#dislike-food').replaceChildren(Object.assign(node('option','Choose a food'),{value:''}),...catalogue.filter(i=>!chosen.has(i.id)).map(i=>Object.assign(node('option',i.name),{value:i.id})));
}
async function dislikeAction(call,done){$('#dislike-fieldset').disabled=true;$('#dislike-message').textContent='Saving...';
    try{renderDislikes(await call());$('#dislike-message').textContent=done;}catch(error){$('#dislike-message').textContent=error.message;}finally{$('#dislike-fieldset').disabled=false;}}
$('#dislike-form').addEventListener('submit',e=>{e.preventDefault();const id=$('#dislike-food').value,name=$('#dislike-food').selectedOptions[0]?.textContent;if(!id)return;
    dislikeAction(()=>api(`${dislikes}/${encodeURIComponent(id)}`,{method:'PUT'}),`${name} will be left out of new meal plans.`);});
try{await requireUser();form.elements.timezone.value=Intl.DateTimeFormat().resolvedOptions().timeZone||'Australia/Sydney';try{fill(await api('/api/v1/health/profile'));$('#message').textContent='';}catch(e){if(e.code!=='PROFILE_REQUIRED')throw e;$('#message').textContent='To get started, complete your health profile.';}fields.disabled=false;fillAddress($('#address-form'),(await api('/api/v1/procurement/address')).address);$('#address-fieldset').disabled=false;
    catalogue=(await api('/api/v1/planning/catalog')).sort((a,b)=>a.name.localeCompare(b.name));renderDislikes(await api(dislikes));$('#dislike-fieldset').disabled=false;}catch(e){$('#message').textContent=e.message;}
