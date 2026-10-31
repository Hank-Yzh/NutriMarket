// Optional body measurements shared by the first-login questionnaire and the health profile page.
// Height, weight and year of birth are used together to calculate daily energy and protein targets.
const el = (tag, text, props = {}) => { const e = document.createElement(tag); if (text != null) e.textContent = text; return Object.assign(e, props); };
const sexes = [['', 'Prefer not to say'], ['FEMALE', 'Female'], ['MALE', 'Male']];
const activities = [['', 'Not sure (light activity is assumed)'], ['SEDENTARY', 'Mostly sitting, little exercise'],
    ['LIGHT', 'Light exercise 1–3 days a week'], ['MODERATE', 'Moderate exercise 3–5 days a week'],
    ['ACTIVE', 'Hard exercise 6–7 days a week'], ['VERY_ACTIVE', 'Physical job or training twice a day']];
function number(label, name, min, max, step, placeholder) {
    const wrap = el('label', label);
    wrap.append(el('input', null, {name, type: 'number', min, max, step, placeholder, inputMode: 'decimal'}));
    return wrap;
}
function select(label, name, options) {
    const wrap = el('label', label), s = el('select', null, {name});
    for (const [value, text] of options) s.append(el('option', text, {value}));
    wrap.append(s);return wrap;
}
/** The measurement inputs, laid out in the page's form grid. */
export function bodyFields() {
    const grid = el('div', null, {className: 'form-grid body-fields'});
    grid.append(number('Height (cm)', 'heightCm', 100, 250, 0.1, 'e.g. 170'), number('Weight (kg)', 'weightKg', 30, 300, 0.1, 'e.g. 65'),
        number('Year of birth', 'birthYear', 1920, new Date().getFullYear() - 16, 1, 'e.g. 1995'),
        select('Sex', 'sex', sexes), select('Activity level', 'activityLevel', activities));
    return grid;
}
/** The measurements entered in the form, or null when every field is blank. */
export function readBody(form) {
    const value = name => String(form.elements[name]?.value ?? '').trim();
    const body = {heightCm: value('heightCm') ? Number(value('heightCm')) : null, weightKg: value('weightKg') ? Number(value('weightKg')) : null,
        birthYear: value('birthYear') ? Number(value('birthYear')) : null, sex: value('sex') || null, activityLevel: value('activityLevel') || null};
    return Object.values(body).some(v => v !== null) ? body : null;
}
export function fillBody(form, body) {
    for (const name of ['heightCm', 'weightKg', 'birthYear', 'sex', 'activityLevel']) form.elements[name].value = body?.[name] ?? '';
}
