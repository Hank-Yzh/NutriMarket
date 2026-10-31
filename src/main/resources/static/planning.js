import {api} from './api.js';
import {requireUser,node,uuid} from './dietary-ui.js';
import {$} from './health-ui.js';
import {card} from './planning-ui.js';
let page=1,assessmentPage=0,busy=false;const selected=new URLSearchParams(location.search).get('assessmentId');
async function load(n=page){const data=await api(`/api/v1/planning/plans?page=${n}&size=10`);page=n;$('#plan-list').replaceChildren(...data.items.map(p=>card(p,'/plan.html?id='+encodeURIComponent(p.planId))));if(!data.items.length)$('#plan-list').append(node('p','No meal plans yet. Select a cleared assessment to start.'));$('#page-info').textContent=`Page ${page}`;$('#previous').disabled=page<=1;$('#next').disabled=data.items.length<10;}
function option(a){if(a.stale||a.planningClearance!=='CLEARED'||[...$('#assessment').options].some(o=>o.value===a.assessmentId))return;const o=node('option',`${a.periodStart} – ${a.periodEnd} · Profile v${a.profileVersion}`);o.value=a.assessmentId;$('#assessment').append(o);}
async function assessments(){const d=await api(`/api/v1/health/assessments?page=${assessmentPage+1}&size=50`);assessmentPage++;d.items.forEach(option);$('#more-assessments').hidden=d.items.length<50;$('#assessment-help').textContent=$('#assessment').options.length>1?'Only cleared assessments using the current profile are shown.':'No cleared assessment found. Complete your health assessment first.';}
async function work(fn){if(busy)return;busy=true;$('#generate').disabled=true;$('#message').textContent='Processing. Generation may take several minutes...';try{await fn();$('#message').textContent='';}catch(e){$('#message').textContent=e.message+' If generation was submitted, refresh the plan list before submitting again.';}finally{busy=false;$('#generate').disabled=false;}}
$('#generate-form').addEventListener('submit',e=>{e.preventDefault();return work(async()=>{const p=await api('/api/v1/planning/plans',{method:'POST',body:{assessmentId:$('#assessment').value,weekStart:$('#week-start').value,aiConsent:$('#ai-consent').checked}});location.assign('/plan.html?id='+encodeURIComponent(p.planId));});});
$('#refresh').onclick=()=>work(()=>load());$('#previous').onclick=()=>work(()=>load(page-1));$('#next').onclick=()=>work(()=>load(page+1));$('#more-assessments').onclick=()=>work(assessments);
try{await requireUser();await work(async()=>{await load();await assessments();if(uuid(selected)){option(await api('/api/v1/health/assessments/'+encodeURIComponent(selected)));$('#assessment').value=selected;}
 let zone;try{zone=(await api('/api/v1/health/profile')).information.timezone;}catch(e){if(e.code!=='PROFILE_REQUIRED')throw e;}
 const parts=new Intl.DateTimeFormat('en-CA',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());const part=t=>parts.find(p=>p.type===t).value;
 const today=new Date(`${part('year')}-${part('month')}-${part('day')}T00:00:00Z`),input=$('#week-start');input.min=today.toISOString().slice(0,10);
 const tomorrow=new Date(today);tomorrow.setUTCDate(tomorrow.getUTCDate()+1);input.value=tomorrow.toISOString().slice(0,10);
 const latest=new Date(today);latest.setUTCDate(latest.getUTCDate()+84);input.max=latest.toISOString().slice(0,10);
 });}catch(e){$('#message').textContent=e.message;}
