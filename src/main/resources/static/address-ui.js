// Delivery address inputs shared by the health profile page and the basket editor.
// The address is stored apart from the health profile and is only copied into home-delivery store orders.
const el = (tag, text, props = {}) => { const e = document.createElement(tag); if (text != null) e.textContent = text; return Object.assign(e, props); };
const states = ['NSW', 'VIC', 'QLD', 'SA', 'WA', 'TAS', 'ACT', 'NT'];
const names = ['recipient', 'line1', 'line2', 'suburb', 'state', 'postcode', 'instructions'];
function input(label, name, props) {
    const wrap = el('label', label);wrap.append(el('input', null, {name, ...props}));return wrap;
}
/** The address inputs. Required fields are marked required only where the whole form is the address. */
export function addressFields(required = true) {
    const grid = el('div', null, {className: 'form-grid address-fields'}), state = el('label', 'State'), select = el('select', null, {name: 'state', required});
    select.append(el('option', 'Choose', {value: ''}));for (const s of states) select.append(el('option', s, {value: s}));state.append(select);
    grid.append(input('Recipient name', 'recipient', {maxLength: 100, required, autocomplete: 'name'}),
        input('Street address', 'line1', {maxLength: 200, required, autocomplete: 'address-line1'}),
        input('Unit, building (optional)', 'line2', {maxLength: 200, autocomplete: 'address-line2'}),
        input('Suburb', 'suburb', {maxLength: 100, required, autocomplete: 'address-level2'}), state,
        input('Postcode', 'postcode', {maxLength: 4, pattern: '\\d{4}', inputMode: 'numeric', required, autocomplete: 'postal-code'}),
        input('Delivery instructions (optional)', 'instructions', {maxLength: 300}));
    return grid;
}
/** The address entered under root, or null when every field is blank. */
export function readAddress(root) {
    const address = {};
    for (const name of names) { const v = String(root.querySelector(`[name="${name}"]`)?.value ?? '').trim(); address[name] = v || null; }
    return Object.values(address).some(v => v !== null) ? address : null;
}
export function fillAddress(root, address) {
    for (const name of names) { const field = root.querySelector(`[name="${name}"]`); if (field) field.value = address?.[name] ?? ''; }
}
export const sameAddress = (a, b) => names.every(n => (a?.[n] ?? null) === (b?.[n] ?? null));
