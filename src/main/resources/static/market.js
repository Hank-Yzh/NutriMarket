import {api} from './api.js';
import {requireUser,node,uuid} from './dietary-ui.js';
import {$} from './health-ui.js';
let planPage=0,busy=false;const plans=new Map(),selected=new URLSearchParams(location.search).get('planId');
function add(p){if(p.state!=='PUBLISHED'||p.stale||plans.has(p.planId))return;plans.set(p.planId,p);const o=node('option',`${p.weekStart} – ${p.weekEnd} · Plan v${p.version}`);o.value=p.planId;$('#plan-select').append(o);}
async function loadPlans(){const data=await api(`/api/v1/planning/plans?page=${planPage+1}&size=50`);planPage++;data.items.forEach(add);$('#more-plans').hidden=data.items.length<50;}
async function work(fn){if(busy)return;busy=true;$('#match').disabled=true;$('#message').textContent='Loading catalogue data and matching products...';try{await fn();$('#message').textContent='';}catch(e){$('#message').textContent=e.message+' If matching was submitted, select the same plan and match again to reopen its result.';}finally{busy=false;$('#match').disabled=false;}}
$('#match-form').onsubmit=e=>{e.preventDefault();work(async()=>{const p=plans.get($('#plan-select').value);if(!p)throw new Error('Choose a current published plan.');const r=await api('/api/v1/market/results',{method:'POST',body:{planId:p.planId,planVersion:p.version}});location.assign('/market-result.html?id='+encodeURIComponent(r.resultId));});};
$('#more-plans').onclick=()=>work(loadPlans);
try{await requireUser();await work(async()=>{await loadPlans();if(uuid(selected)){add(await api('/api/v1/planning/plans/'+encodeURIComponent(selected)));$('#plan-select').value=selected;}});}catch(e){$('#message').textContent=e.message;}
