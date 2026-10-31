import {api, logout} from './api.js';
export const statuses = {
    PENDING_MEMBER_CONFIRMATION: 'Awaiting confirmation', VALIDATED: 'Validated', DEFERRED: 'Awaiting retry',
    REPLACEMENT_REQUIRED: 'More meal details needed', ABANDONED: 'Abandoned', EXPIRED: 'Expired'
};
export const statusHelp = {
    PENDING_MEMBER_CONFIRMATION: 'Check the foods and portions below, review the nutrition totals, then confirm your meal.',
    VALIDATED: 'This meal has been confirmed. Continue to health assessment below.',
    DEFERRED: 'Analysis is incomplete. Check the failure reason and retry when the service recovers, without uploading the photo again.',
    REPLACEMENT_REQUIRED: 'This meal could not be identified. Create a new record with a clearer photo or a more detailed meal description.',
    ABANDONED: 'This record has been abandoned and will not be sent to the Health Assessment Agent.',
    EXPIRED: 'The confirmation period has expired. This record can no longer be changed or confirmed. Upload a new record.'
};
export const time = value => value ? new Date(value).toLocaleString('en-AU') : '—';
export const number = value => value == null ? '—' : Number(value).toLocaleString('en-AU', {maximumFractionDigits: 2});
export const confidence = value => value == null ? '—' : `${number(value * 100)}%`;
export const detailUrl = id => '/record.html?id=' + encodeURIComponent(id);
export const uuid = value => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value || '');
export function actions(record) {
    return {
        confirm: record.status === 'PENDING_MEMBER_CONFIRMATION',
        retry: record.status === 'DEFERRED',
        edit: ['PENDING_MEMBER_CONFIRMATION', 'DEFERRED'].includes(record.status) && record.estimates?.length > 0,
        abandon: ['PENDING_MEMBER_CONFIRMATION', 'DEFERRED', 'REPLACEMENT_REQUIRED'].includes(record.status),
        validated: record.status === 'VALIDATED'
    };
}
export function node(tag, text) {
    const result = document.createElement(tag);
    if (text != null) result.textContent = text;
    return result;
}
export function table(headers, rows) {
    const result = node('table');
    const head = node('thead'), tr = node('tr');
    for (const value of headers) { const th = node('th', value); th.scope = 'col'; tr.append(th); }
    head.append(tr); result.append(head);
    const body = node('tbody');
    for (const values of rows) {
        const row = node('tr');
        for (const value of values) row.append(node('td', value));
        body.append(row);
    }
    result.append(body); return result;
}
// Everyday names for the nutrients people recognise from food labels; other USDA components are not shown.
export const nutrientNames = {'1008': 'Energy', '1062': 'Energy', '1003': 'Protein', '1004': 'Fat, total', '1258': 'Saturated fat',
    '1005': 'Carbohydrate', '2000': 'Sugars', '1063': 'Sugars', '1079': 'Dietary fibre', '1093': 'Sodium', '1087': 'Calcium',
    '1089': 'Iron', '1092': 'Potassium', '1090': 'Magnesium', '1095': 'Zinc', '1106': 'Vitamin A', '1162': 'Vitamin C',
    '1114': 'Vitamin D', '1178': 'Vitamin B12'};
export function nutrients(values = {}) {
    const known = Object.entries(values).filter(([id]) => nutrientNames[id]);
    if (!known.length) return node('p', 'No nutrition data available. Missing data does not mean zero.');
    return table(['Nutrient', 'Amount', 'Unit'], known.map(([id, value]) => [nutrientNames[id], number(value.amount), value.unit]));
}
export async function requireUser() {
    document.querySelector('#logout')?.addEventListener('click', () => logout());
    const user = await api('/api/v1/auth/me');
    const label = document.querySelector('#username');
    if (label) label.textContent = user.username;
    import('./questionnaire.js').then(m => m.offerQuestionnaire()).catch(() => {});
    return user;
}
