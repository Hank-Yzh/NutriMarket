import {api} from './api.js';
import {requireUser,node,time} from './dietary-ui.js';
import {$,base,states,link,money,runner,chip,lines} from './procurement-ui.js';
let page=1,resultPage=0,hasNext=false;const work=runner(()=>{$('#previous').disabled=page===1;$('#next').disabled=!hasNext;});
async function load(){const data=await api(`${base}/baskets?page=${page}`);hasNext=data.items.length===10;$('#page-info').textContent=`Page ${page}`;$('#baskets').replaceChildren();for(const b of data.items){const row=node('article');row.className='product-match';row.append(link(`${b.source.originalPlanningPackage.weekStart} · ${states[b.state]}`,`/basket.html?id=${b.basketId}`),node('p',`${money(b.evaluation.total,b.settings.currency)} · ${time(b.updatedAt)}`));$('#baskets').append(row);}if(!data.items.length)$('#baskets').append(node('p','No baskets yet. One is built automatically after your next cleared health assessment.'));}
async function results(){const data=await api(`/api/v1/market/results?page=${resultPage+1}&size=20`);resultPage++;for(const r of data.items)if(['COMPLETED','NEEDS_ATTENTION'].includes(r.state)){const o=node('option',`${r.planning.weekStart} · Plan v${r.planVersion} · Market revision ${r.revision}`);o.value=r.resultId;$('#result-select').append(o);}$('#more-results').hidden=data.items.length<20;}
$('#start-form').onsubmit=e=>{e.preventDefault();work(async()=>{if(!$('#result-select').value)throw new Error('Choose a completed market result first.');const b=await api(base+'/baskets',{method:'POST',body:{resultId:$('#result-select').value}});location.assign('/basket.html?id='+b.basketId);});};
const steps=[['Health assessment',s=>s.assessmentId&&'/assessment.html?id='+s.assessmentId],['Meal plan',s=>s.planId&&'/plan.html?id='+s.planId],['Product matching',s=>s.resultId&&'/market-result.html?id='+s.resultId],['Basket',s=>s.basketId&&'/basket.html?id='+s.basketId]];
const reached={NO_PROFILE:0,NO_ASSESSMENT:0,CONSENT_REQUIRED:1,PLANNING:1,PLAN_PAUSED:1,MATCHING:2,MATCH_PAUSED:2,BUILDING_BASKET:3,BASKET_PAUSED:3,BASKET_READY:4},nextStep={NO_PROFILE:'/profile.html',NO_ASSESSMENT:'/health.html',CONSENT_REQUIRED:'/profile.html'};let timer;
async function pipeline(){clearTimeout(timer);const s=await api(base+'/pipeline'),at=reached[s.stage];
    $('#pipeline-steps').replaceChildren(...steps.map(([label,url],i)=>{const tone=i<at?'good':i>at?'muted':['PLAN_PAUSED','CONSENT_REQUIRED','MATCH_PAUSED','BASKET_PAUSED'].includes(s.stage)?'bad':'warn';const c=chip(`${i<at?'✓ ':''}${label}`,tone);const href=url(s);if(!href)return c;const a=link('',href);a.append(c);return a;}));
    $('#pipeline-message').replaceChildren(node('span',s.message+' '));if(nextStep[s.stage])$('#pipeline-message').append(link({NO_PROFILE:'Open health profile →',NO_ASSESSMENT:'Open health assessment →',CONSENT_REQUIRED:'Open health profile →'}[s.stage],nextStep[s.stage]));
    if(['MATCH_PAUSED','BASKET_PAUSED'].includes(s.stage)){
        $('#pipeline-message').append(link('Open product matching →',s.resultId?'/market-result.html?id='+s.resultId:'/market.html?planId='+s.planId));
        if(s.stage==='BASKET_PAUSED'&&s.resultId){
            const retry=node('button','Try building basket');retry.type='button';
            retry.onclick=()=>work(async()=>{const b=await api(base+'/baskets',{method:'POST',body:{resultId:s.resultId}});location.assign('/basket.html?id='+b.basketId);});
            $('#pipeline-message').append(retry);
        }
    }
    $('#pipeline-issues').replaceChildren(...(s.stage==='PLAN_PAUSED'?s.planIssues.slice(0,5):[]).map(i=>node('li',i)));
    $('#pipeline-basket').hidden=s.stage!=='BASKET_READY';
    if(s.stage==='BASKET_READY'){const b=await api(`${base}/baskets/${s.basketId}`);$('#pipeline-total').textContent=`${states[b.state]} · ${money(b.evaluation.total,b.settings.currency)}`;$('#pipeline-open').href='/basket.html?id='+b.basketId;lines($('#pipeline-lines'),b.evaluation.lines,b.materialChanges||[]);}
    // Planning and matching call Qwen and can take a few minutes; keep checking until they finish.
    if(['PLANNING','MATCHING','MATCH_PAUSED','BUILDING_BASKET'].includes(s.stage))timer=setTimeout(()=>pipeline().catch(e=>{$('#message').textContent=e.message;}),10000);
}
$('#pipeline-refresh').onclick=()=>work(pipeline);
$('#previous').onclick=()=>{page--;work(load);};$('#next').onclick=()=>{page++;work(load);};$('#reload').onclick=()=>work(load);$('#more-results').onclick=()=>work(results);
try{await requireUser();const access=await api(base+'/access');$('#expert-link').hidden=!access.expert;$('#report-link').hidden=!access.reportStore;await work(async()=>{await pipeline();await results();await load();});}catch(e){$('#message').textContent=e.message;}
