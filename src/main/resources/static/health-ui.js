import {node, table, number, time} from './dietary-ui.js';
export const $ = selector => document.querySelector(selector);
export const clearanceLabels = {NOT_READY:'More records needed',CLEARED:'Cleared for planning',BLOCKED_FOR_EXPERT_REVIEW:'Awaiting expert review',NOT_ELIGIBLE_FOR_AUTOMATION:'Not eligible for automated planning',REFER_TO_HEALTHCARE:'Referral to a healthcare professional'};
export const riskLabels = {NOT_ASSESSED:'Not assessed',WITHIN_TARGETS:'Within personal targets',TARGET_DEVIATION:'Target deviation',POTENTIAL_HIGH_RISK:'Expert review required',LIMITED_DATA:'Assessment from partial records'};
export const findingLabels = {NOT_EVALUATED:'Awaiting assessment',MISSING_DATA:'Missing data',BELOW_TARGET:'Below personal target',ABOVE_TARGET:'Above personal target',WITHIN_TARGET:'Within target',RECORDED_BELOW_TARGET:'Recorded amount below target; meals missing',RECORDED_ABOVE_TARGET:'Recorded amount above target; meals missing',RECORDED_WITHIN_TARGET:'Recorded amount within target; meals missing'};
export const decisionLabels={CLEARED_FOR_PLANNING:'Cleared for planning',MODIFIED_AND_CLEARED:'Cleared with additional constraints',NOT_ELIGIBLE_FOR_AUTOMATION:'Not eligible for automated planning',REFER_TO_HEALTHCARE:'Referral to a healthcare professional'};
export const lines = value => value.split(/\r?\n/).map(x=>x.trim()).filter(Boolean);
export function comparisonTable(a) {
    return table(['Nutrient','Recorded total','Recorded daily average','Daily target','Comparison','Days below / above target'],a.comparisons.map(c=>[
        c.name,`${number(c.total)} ${c.unit}`,`${number(c.dailyAverage)} ${c.unit}`,`${number(c.minimum)}–${number(c.maximum)} ${c.unit}`,
        findingLabels[c.finding]||c.finding,`${c.belowDays} / ${c.aboveDays}`]));
}
export function listItems(target,values) {target.replaceChildren(...values.map(value=>node('li',value)));}
export function badge(a) {const b=node('span',a.stale?'Profile updated':clearanceLabels[a.planningClearance]);b.className='health-badge';b.dataset.state=a.stale?'NOT_READY':a.planningClearance;return b;}
// Human-in-the-loop marker for Agent 2. Compact cards mark completed reviews only, since the clearance badge already says "Awaiting expert review".
export function reviewTag(a,compact=false) {
    const d=a.expertDecision,pending=!d&&a.planningClearance==='BLOCKED_FOR_EXPERT_REVIEW'&&!a.stale;
    if(!d&&(!pending||compact))return null;
    const tag=node('span',d?(compact?'Human reviewed':`Human reviewed · ${decisionLabels[d.decision]}`):'Human review · Pending');tag.className='review-tag';
    tag.dataset.state=!d?'pending':['CLEARED_FOR_PLANNING','MODIFIED_AND_CLEARED'].includes(d.decision)?'cleared':'held';return tag;
}
export function summaryCard(a,href,showReviewTag=true) {
    const card=node('article');card.className='assessment-card';
    const top=node('div');top.className='section-heading';const link=node('a',`${a.periodStart} – ${a.periodEnd}`);link.href=href;
    const badges=node('div');badges.className='card-badges';const tag=showReviewTag&&reviewTag(a,true);if(tag)badges.append(tag);badges.append(badge(a));top.append(link,badges);
    card.append(top,node('p',`${a.intakeCount} meals · ${a.observedDays??a.coveredDays}/7 days with records · ${riskLabels[a.riskStatus]}`));return card;
}
const bodyText=b=>!b?'Not provided':[b.heightCm&&`${b.heightCm} cm`,b.weightKg&&`${b.weightKg} kg`,b.birthYear&&`born ${b.birthYear}`,b.sex&&b.sex.toLowerCase(),b.activityLevel&&`${b.activityLevel.toLowerCase().replace('_',' ')} activity`].filter(Boolean).join(' · ')||'Not provided';
export function showProfile(target,p) {
    const info=p.information;target.replaceChildren(table(['Profile','Details'],[
        ['Version',p.version],['Goals',info.goals],['Timezone',info.timezone],['Allergies',info.allergies.join(', ')||'None'],
        ['Dietary restrictions',info.restrictions.join(', ')||'None'],['Health conditions',info.conditions.join(', ')||'None'],['Preferences',info.preferences||'Not provided'],['Body measurements',bodyText(info.body)],['Target source',info.targetSource],...(info.targetRationale?[['Target rationale',info.targetRationale]]:[])
    ]));
}
const nutrientNames={'1008':'Energy','1003':'Protein','1004':'Fat','1005':'Carbohydrate','1079':'Dietary fibre','1093':'Sodium','1087':'Calcium','1089':'Iron'};
export function targetBasis(target,source,rationale) {
    const parts=[node('p',`Daily targets: ${source||'Not recorded'}.`)];
    if(rationale){const why=node('p',rationale);why.className='ai-explanation';parts.push(why);}
    target.replaceChildren(...parts);
}
export function targetTable(targets) {return table(['Nutrient','Daily target'],Object.entries(targets).map(([id,t])=>[nutrientNames[id]||id,`${number(t.minimum)}–${number(t.maximum)} ${t.unit}`]));}
export function showReview(target,r) {target.replaceChildren(table(['Review information','Details'],[['Decision',decisionLabels[r.decision]],['Reviewer',r.expertUsername],['Time',time(r.decidedAt)],['Rationale',r.rationale],['Additional constraints',r.additionalConstraints.join('; ')||'None']]));}
