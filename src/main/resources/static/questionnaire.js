import {api} from './api.js';
import {bodyFields, readBody} from './body-ui.js';
// First-login health questionnaire. Shown on member pages until a health profile exists.
// Restriction and condition options use the wording planning and the routine-condition list recognise.
const goals = ['Lose weight gradually', 'Maintain a healthy weight', 'Gain weight', 'Build muscle',
    'Eat more balanced meals', 'Eat less sugar', 'Eat less salt', 'Keep track of what I eat'];
const mealStyles = ['Asian', 'Mediterranean', 'High-protein / fitness', 'Simple Western', 'Light & fresh'];
const restrictions = ['Vegetarian', 'Vegan', 'Pescatarian', 'Gluten free', 'Dairy free', 'Lactose free',
    'Nut free', 'Peanut free', 'Soy free', 'Egg free'];
const conditions = ['Type 2 diabetes', 'Prediabetes', 'High blood pressure', 'High cholesterol', 'Overweight'];
const skipKey = 'nutrimarket-questionnaire-skipped';
const el = (tag, text, props = {}) => { const e = document.createElement(tag); if (text != null) e.textContent = text; return Object.assign(e, props); };
const lines = value => value.split(/[\r\n,]+/).map(x => x.trim()).filter(Boolean);
function choices(name, values) {
    const grid = el('div', null, {className: 'choice-grid'});
    for (const value of values) { const label = el('label'); label.append(el('input', null, {type: 'checkbox', name, value}), el('span', value)); grid.append(label); }
    return grid;
}
function question(title, help, ...content) {
    const box = el('fieldset', null, {className: 'questionnaire-step'});box.append(el('legend', title));
    if (help) box.append(el('p', help, {className: 'field-help'}));
    box.append(...content);return box;
}
function consent(name, text) {const label = el('label', null, {className: 'consent'});label.append(el('input', null, {type: 'checkbox', name}), el('span', text));return label;}
function build() {
    const dialog = el('dialog', null, {className: 'change-dialog questionnaire'}), form = el('form');
    const message = el('p', '', {className: 'field-help', role: 'status'});
    form.append(el('h2', 'Welcome to NutriMarket'), el('p', 'A few questions set up your health profile. You can change any answer later in Health profile.', {className: 'muted'}),
        question('What are your health goals?', 'Choose all that apply.', choices('goal', goals), el('input', null, {name: 'otherGoal', maxLength: 200, placeholder: 'Another goal (optional)'})),
        question('About you', 'Optional. Height, weight and year of birth are used together to calculate your daily energy and protein targets; leave them blank to use general adult targets.', bodyFields()),
        question('Do you have any food allergies?', 'Type each allergy on its own line, for example "peanuts" or "shellfish". Leave blank if none.', el('textarea', null, {name: 'allergies', rows: 3, maxLength: 3030})),
        question('Do you follow any dietary restrictions?', 'Choose all that apply.', choices('restriction', restrictions), el('input', null, {name: 'otherRestriction', maxLength: 100, placeholder: 'Another restriction (optional)'})),
        question('Any health conditions to consider?', 'Choose all that apply. Other conditions are reviewed by a human expert before meal planning.', choices('condition', conditions), el('input', null, {name: 'otherCondition', maxLength: 100, placeholder: 'Another condition (optional)'})),
        question('What kind of meals do you enjoy?', 'Meal plans are written in these styles. Choose all that apply.', choices('mealStyle', mealStyles)),
        question('Any other food preferences?', null, el('textarea', null, {name: 'preferences', rows: 2, maxLength: 1000, placeholder: 'For example: mild flavours, quick breakfasts'})),
        question('AI features', null,
            consent('aiSummaryConsent', 'Allow Qwen to generate my daily nutrition targets from my health goals and, if given, my height, weight, age, sex and activity level, and to explain an assessment when I ask. Account identity, meal photos, allergies, restrictions, conditions and preferences are not sent. Without consent, targets are calculated from my measurements, or general adult targets are used.'),
            consent('autoPlanningConsent', 'Automatically plan my meals, match products and build my shopping basket after each cleared health assessment. To plan meals, my goals, preferences, allergies, dietary restrictions, health conditions, daily targets and intake comparisons are sent to Qwen; account identity and meal photos are not. Nothing is ordered until I confirm the basket.')),
        message);
    const actions = el('div', null, {className: 'dialog-actions'}), skip = el('button', 'Skip for now', {type: 'button', className: 'text-button'}), save = el('button', 'Save my profile', {type: 'submit', className: 'primary'});
    actions.append(skip, save);form.append(actions);dialog.append(form);
    skip.onclick = () => { try { sessionStorage.setItem(skipKey, '1'); } catch { /* Skipping still closes the dialog. */ } dialog.close(); };
    dialog.addEventListener('cancel', () => { try { sessionStorage.setItem(skipKey, '1'); } catch { /* Escape still closes the dialog. */ } });
    form.onsubmit = async event => {
        event.preventDefault();const data = new FormData(form);
        const picked = (name, other) => [...data.getAll(name), ...lines(String(data.get(other) || ''))];
        const goalText = picked('goal', 'otherGoal').join('; ');
        if (!goalText) { message.textContent = 'Choose at least one health goal.'; return; }
        const body = {expectedVersion: 0, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Australia/Sydney',
            goals: goalText.slice(0, 500), allergies: lines(String(data.get('allergies') || '')), restrictions: picked('restriction', 'otherRestriction'),
            conditions: picked('condition', 'otherCondition'), mealStyles: data.getAll('mealStyle'), preferences: String(data.get('preferences') || '').trim(),
            aiSummaryConsent: !!data.get('aiSummaryConsent'), autoPlanningConsent: !!data.get('autoPlanningConsent'), body: readBody(form)};
        save.disabled = true;message.textContent = body.aiSummaryConsent ? 'Saving and generating your daily targets...' : 'Saving...';
        try { await api('/api/v1/health/profile', {method: 'PUT', body}); dialog.close(); location.reload(); }
        catch (error) { message.textContent = error.message; save.disabled = false; }
    };
    document.body.append(dialog);return dialog;
}
export async function offerQuestionnaire() {
    if (document.body.classList.contains('expert-page') || location.pathname === '/profile.html') return;
    try { if (sessionStorage.getItem(skipKey)) return; } catch { /* Storage may be blocked; still offer the questionnaire. */ }
    try { await api('/api/v1/health/profile'); }
    catch (error) { if (error.code === 'PROFILE_REQUIRED') build().showModal(); }
}
