import {api} from './api.js';
import {requireUser,node,table,time} from './dietary-ui.js';
import {$,summaryCard,comparisonTable,showProfile,showReview,listItems,lines,reviewTag,decisionLabels} from './health-ui.js';
const size=10,pages={pending:1,completed:1};let selected,busy=false;
// Mirrors HealthService.review: what each ExpertDecision does to the assessment and whether Agent 3 may start.
const guide=[
    ['CLEARED_FOR_PLANNING','Keeps the assessment and its constraints; clearance becomes CLEARED.','✓ Yes'],
    ['MODIFIED_AND_CLEARED','Adds the constraints you specify (existing allergies and restrictions stay), then sets CLEARED.','✓ Yes'],
    ['NOT_ELIGIBLE_FOR_AUTOMATION','Marks automated planning as unsuitable; the member sees this on their assessment.','✗ No'],
    ['REFER_TO_HEALTHCARE','Records the referral; the member is advised to consult a healthcare professional.','✗ No']];
function card(a){
    const c=summaryCard(a,'#review-panel',false),d=a.expertDecision;
    c.querySelector('a').addEventListener('click',e=>{e.preventDefault();work(()=>select(a.assessmentId));});
    c.append(node('p',d?`Member ${a.memberId.slice(0,8)} · ${decisionLabels[d.decision]} by ${d.expertUsername} · ${time(d.decidedAt)}`:`Member ${a.memberId.slice(0,8)} · ${a.reasons.length} reason(s) flagged`));
    if(d)c.append(node('p',`Rationale: ${d.rationale}`));
    return c;
}
async function load(kind){
    const result=await api(`/api/v1/health/expert/${kind==='pending'?'queue':'reviewed'}?page=${pages[kind]}&size=${size}`);
    const list=$(kind==='pending'?'#queue':'#completed');list.replaceChildren(...result.items.map(card));
    if(!result.items.length)list.append(node('p',kind==='pending'?'No assessments are currently awaiting review.':'No reviews have been completed yet.'));
    $(`#${kind}-count`).textContent=result.total;$(`#${kind}-page`).textContent=`Page ${pages[kind]} of ${Math.max(1,Math.ceil(result.total/size))}`;
    $(`#${kind}-previous`).disabled=pages[kind]<=1;$(`#${kind}-next`).disabled=pages[kind]*size>=result.total;
}
function show(kind){for(const k of ['pending','completed']){$(`#tab-${k}`).setAttribute('aria-selected',String(k===kind));$(`#${k}-panel`).hidden=k!==kind;}$('#review-panel').hidden=true;selected=null;}
async function select(id){
    const result=await api('/api/v1/health/expert/assessments/'+encodeURIComponent(id));const a=result.assessment,d=a.expertDecision;selected=a;
    $('#review-panel').hidden=false;$('#review-title').textContent=`Week ${a.periodStart} – ${a.periodEnd}`;
    const tag=reviewTag(a);$('#review-tag').hidden=!tag;if(tag){$('#review-tag').textContent=tag.textContent;$('#review-tag').dataset.state=tag.dataset.state;}
    $('#review-heading').textContent=`Member ${a.memberId} · Assessment ${a.assessmentId} · Profile v${a.profileVersion}${a.stale?' · the member has since updated their profile':''}`;
    listItems($('#review-reasons'),a.reasons);$('#review-comparisons').replaceChildren(comparisonTable(a));showProfile($('#review-profile'),result.profile);
    $('#review-result').hidden=!d;if(d)showReview($('#review-decision'),d);
    const open=!d&&!a.stale&&a.planningClearance==='BLOCKED_FOR_EXPERT_REVIEW';$('#review-form').reset();$('#review-form').hidden=!open;$('#review-note').hidden=!open;
    $('#review-panel').scrollIntoView({behavior:'smooth'});
}
async function work(fn){if(busy)return;busy=true;$('#message').textContent='Processing...';try{await fn();$('#message').textContent='';}catch(e){$('#message').textContent=e.message;}finally{busy=false;}}
for(const kind of ['pending','completed']){
    $(`#tab-${kind}`).addEventListener('click',()=>show(kind));
    $(`#${kind}-previous`).addEventListener('click',()=>work(async()=>{pages[kind]--;await load(kind);}));
    $(`#${kind}-next`).addEventListener('click',()=>work(async()=>{pages[kind]++;await load(kind);}));
}
$('#reload').addEventListener('click',()=>work(async()=>{await load('pending');await load('completed');}));
$('#review-form').addEventListener('submit',e=>{e.preventDefault();if(!selected||busy)return;const data=new FormData(e.target);if(!confirm('Submit this expert decision? The review will be retained in the audit record.'))return;
    work(async()=>{await api('/api/v1/health/expert/assessments/'+encodeURIComponent(selected.assessmentId)+'/review',{method:'POST',body:{decision:data.get('decision'),rationale:String(data.get('rationale')).trim(),additionalConstraints:lines(String(data.get('additionalConstraints')))}});
        const id=selected.assessmentId;pages.pending=1;pages.completed=1;await load('pending');await load('completed');show('completed');await select(id);});});
try{
    await requireUser();const access=await api('/api/v1/health/expert/access');
    $('#access-denied').hidden=access.expert;$('#expert-content').hidden=!access.expert;$('#message').textContent='';
    $('#decision-guide').replaceChildren(table(['Decision','What Agent 2 does','Can Agent 3 start?'],guide.map(([key,effect,start])=>[decisionLabels[key],effect,start])));
    if(access.expert)await work(async()=>{await load('pending');await load('completed');});
}catch(e){$('#message').textContent=e.message;}
