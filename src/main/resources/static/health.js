import {api} from './api.js';
import {requireUser,node} from './dietary-ui.js';
import {$,summaryCard} from './health-ui.js';

let page=1,busy=false,profileReady=false,pendingStart=null;const size=10;
const dialog=$('#coverage-dialog');
async function load(requested=page) {
    const result=await api(`/api/v1/health/assessments?page=${requested}&size=${size}`);page=result.page;
    $('#assessment-list').replaceChildren(...result.items.map(a=>summaryCard(a,'/assessment.html?id='+encodeURIComponent(a.assessmentId))));
    if(!result.items.length)$('#assessment-list').append(node('p','No assessments yet. Complete your health profile, then choose seven dates to assess.'));
    $('#page-info').textContent=`Page ${page}`;$('#previous').disabled=page<=1;$('#next').disabled=result.items.length<size;
}
async function work(fn) {
    if(busy)return;busy=true;$('#generate').disabled=true;$('#message').textContent='Processing...';
    try{await fn();$('#message').textContent='';}catch(e){$('#message').textContent=e.message;}
    finally{busy=false;$('#generate').disabled=!profileReady;}
}
function periodSummary() {
    const start=$('#week-start').value;
    if(!/^\d{4}-\d{2}-\d{2}$/.test(start)){$('#period-summary').textContent='';return;}
    const end=new Date(start+'T00:00:00Z');end.setUTCDate(end.getUTCDate()+6);
    $('#period-summary').textContent=`Assessment period: ${start} – ${end.toISOString().slice(0,10)} (7 days)`;
}
async function generate(periodStart,allowIncomplete=false) {
    const a=await api('/api/v1/health/assessments',{method:'POST',body:{periodStart,allowIncomplete}});
    location.assign('/assessment.html?id='+encodeURIComponent(a.assessmentId));
}
$('#refresh').addEventListener('click',()=>work(()=>load()));
$('#previous').addEventListener('click',()=>work(()=>load(page-1)));
$('#next').addEventListener('click',()=>work(()=>load(page+1)));
$('#week-start').addEventListener('change',periodSummary);
$('#generate-form').addEventListener('submit',e=>{
    e.preventDefault();if(dialog.open)return;
    return work(async()=>{
        const start=$('#week-start').value;
        const coverage=await api('/api/v1/health/assessment-coverage?periodStart='+encodeURIComponent(start));
        if(coverage.missingMeals===0){await generate(start);return;}
        pendingStart=start;const empty=coverage.intakeCount===0;
        $('#coverage-title').textContent=empty?'Add a meal to begin':'Some meals may be missing';
        $('#coverage-summary').textContent=`${coverage.periodStart} – ${coverage.periodEnd}: ${coverage.intakeCount} validated meals across ${coverage.observedDays} of 7 days.`;
        $('#coverage-days').textContent=`Dates with fewer than ${coverage.mealsPerDay} recorded meals: ${coverage.incompleteDays.join(', ')}. This is a reminder, not a requirement to log three meals every day.`;
        $('#coverage-help').textContent=empty?'There are no validated meals in this period yet. Add and confirm at least one meal to assess recorded intake.':'Would you like to add missing meals? You can also continue with the existing records. The assessment will show its limited coverage and will not count missing meals as zero intake.';
        $('#continue-assessment').hidden=empty;dialog.showModal();
    });
});
$('#cancel-assessment').addEventListener('click',()=>{pendingStart=null;dialog.close();});
dialog.addEventListener('cancel',()=>{pendingStart=null;});
$('#add-meals').addEventListener('click',()=>{dialog.close();location.assign('/upload.html');});
$('#continue-assessment').addEventListener('click',()=>{
    if(!pendingStart||busy)return;const start=pendingStart;pendingStart=null;dialog.close();
    return work(()=>generate(start,true));
});
try {
    await requireUser();
    const access=await api('/api/v1/health/expert/access');$('#expert-link').hidden=!access.expert;
    try {
        const p=await api('/api/v1/health/profile');
        $('#profile-summary').textContent=`${p.information.timezone} · ${p.information.goals}`;
        const parts=new Intl.DateTimeFormat('en-CA',{timeZone:p.information.timezone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
        const get=t=>parts.find(p=>p.type===t).value;
        const today=new Date(`${get('year')}-${get('month')}-${get('day')}T00:00:00Z`);
        const latest=new Date(today);latest.setUTCDate(latest.getUTCDate()-7);
        const earliest=new Date(today);earliest.setUTCFullYear(earliest.getUTCFullYear()-2);
        if(earliest.getUTCMonth()!==today.getUTCMonth())earliest.setUTCDate(0);
        const input=$('#week-start');input.max=latest.toISOString().slice(0,10);input.min=earliest.toISOString().slice(0,10);
        const requested=new URLSearchParams(location.search).get('periodStart');
        input.value=/^\d{4}-\d{2}-\d{2}$/.test(requested||'')&&requested>=input.min&&requested<=input.max?requested:input.max;
        if(!input.value)input.value=input.max;
        periodSummary();profileReady=true;$('#generate').disabled=false;
    } catch(e){if(e.code!=='PROFILE_REQUIRED')throw e;$('#profile-summary').textContent=e.message;}
    await work(()=>load(1));
} catch(e){$('#message').textContent=e.message;}
