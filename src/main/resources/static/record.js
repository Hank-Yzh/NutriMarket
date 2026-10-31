import {api} from './api.js';
import {requireUser, uuid, statuses, statusHelp, time, number, confidence, node, table, actions} from './dietary-ui.js';
const id = new URLSearchParams(location.search).get('id');
const path = '/api/v1/dietary-records/' + encodeURIComponent(id);
const $ = selector => document.querySelector(selector);
let current, busy = false, dirty = false, editing = false;
// Nutrition-information-panel rows, in label order. Sub-rows are indented under their parent.
const labelRows = [['Energy',['1008']],['Protein',['1003']],['Fat, total',['1004']],['– Saturated fat',['1258']],
    ['Carbohydrate',['1005']],['– Sugars',['2000','1063']],['Dietary fibre',['1079']],['Sodium',['1093']]];
const vitaminRows = [['Calcium','1087'],['Iron','1089'],['Potassium','1092'],['Magnesium','1090'],['Zinc','1095'],
    ['Vitamin A','1106'],['Vitamin C','1162'],['Vitamin D','1114'],['Vitamin B12','1178']];
const amount = value => `${number(value.amount)} ${value.unit}`;
function nutritionLabel(totals) {
    const rows = labelRows.map(([name, ids]) => {
        const value = ids.map(id => totals[id]).find(Boolean);
        // Food labels in Australia lead with kilojoules; show kcal alongside.
        if (ids[0] === '1008' && totals['1062']) return [name, value ? `${amount(totals['1062'])} (${amount(value)})` : amount(totals['1062'])];
        return [name, value ? amount(value) : 'Not available'];
    });
    return table(['Nutrition information', 'This meal'], rows);
}
function applyMode() {
    const canEdit = !!current && actions(current).edit;
    $('#editor').hidden = !(canEdit && editing); $('#foods').hidden = canEdit && editing; $('#edit-foods').hidden = !canEdit || editing;
}
function updateReviewState() {
    $('#confirm').disabled = busy || dirty || !current || !actions(current).confirm;
    $('#totals-note').textContent = dirty
        ? 'You have unsaved changes. Save and recalculate to update these totals.'
        : 'Calculated for the saved foods and portions.';
    $('#nutrition-totals').classList.toggle('is-stale', dirty);
    $('#confirmation-help').textContent = current?.status === 'VALIDATED' ? 'This meal has been confirmed.'
        : dirty ? 'Save your food and portion changes before confirming.'
        : current?.status === 'PENDING_MEMBER_CONFIRMATION' ? 'Confirm when the foods, portions and nutrition totals above are correct.'
        : 'Complete the meal analysis before confirming, or create a new meal record.';
}
function markDirty() { dirty = true; updateReviewState(); }
function editorRow(food = {name: '', searchTerm: '', portionGrams: 100, confidence: 0, fdcId: null}) {
    const row = node('fieldset'); row.className = 'food-row'; row.foodInput = {...food};
    row.append(node('legend', 'Food'));
    for (const [key, label, type] of [['name','Food name','text'],['portionGrams','Portion (grams)','number']]) {
        const wrapper = node('label', label), input = node('input'); input.name = key; input.type = type;
        input.value = food[key] ?? ''; input.required = true;
        if (type === 'text') input.maxLength = 200;
        if (key === 'portionGrams') { input.min = '0.1'; input.max = '10000'; input.step = 'any'; }
        wrapper.append(input); row.append(wrapper);
    }
    const remove = node('button', 'Remove food'); remove.type = 'button';
    remove.addEventListener('click', () => { row.remove(); markDirty(); $('#add-food').disabled = false; });
    row.append(remove); $('#edit-rows').append(row);
}
function fillEditor() {
    $('#edit-rows').replaceChildren();
    const estimates = current.estimates?.length ? current.estimates : (current.foodItems || []).map(food => food.estimate);
    for (const food of estimates) editorRow(food);
    dirty = false; $('#add-food').disabled = $('#edit-rows').children.length >= 20;
    updateReviewState();
}
function render(record) {
    current = record; dirty = false; editing = false;
    $('#record').hidden = false; $('#status').textContent = statuses[record.status] || record.status;
    $('#status').dataset.state = record.status;
    const lastFailure = record.failures?.[record.failures.length - 1];
    $('#status-help').textContent = ['DEFERRED','REPLACEMENT_REQUIRED'].includes(record.status) && lastFailure
        ? lastFailure.message : statusHelp[record.status] || '';
    $('#metadata').replaceChildren(node('p', 'Meal time: ' + time(record.mealTime)));
    if (record.mealDescription) $('#metadata').append(node('p', 'Your description: ' + record.mealDescription));
    const allowed = actions(record);
    for (const action of ['confirm','retry','abandon']) $('#' + action).hidden = !allowed[action];
    $('#downstream').hidden = !allowed.validated;
    $('#replacement').hidden = !['REPLACEMENT_REQUIRED','EXPIRED','ABANDONED'].includes(record.status);
    const totals = record.nutrients || {};
    $('#nutrients').replaceChildren(Object.keys(totals).length ? nutritionLabel(totals) : node('p', 'No nutrition data available. Missing data does not mean zero.'));
    const vitamins = vitaminRows.filter(([, id]) => totals[id]);
    $('#other-nutrients').hidden = !vitamins.length;
    $('#additional-nutrients').replaceChildren(table(['Vitamin or mineral', 'This meal'], vitamins.map(([name, id]) => [name, amount(totals[id])])));
    $('#record-meta').replaceChildren(table(['Detail', 'Value'], [['Record ID', record.recordId], ['Created', time(record.createdAt)],
        ['Analysed', time(record.analyzedAt)], ['Confirm by', time(record.expiresAt)], ['Confirmed', time(record.confirmedAt)],
        ['Analysis attempts', record.attempts], ['Overall confidence', confidence(record.confidence)], ['Health profile version', record.profileVersion]]));
    const foods = record.estimates?.length ? record.estimates : (record.foodItems || []).map(food => food.estimate);
    $('#foods').replaceChildren(foods.length
        ? table(['Food','Portion'], foods.map(food => [food.name, number(food.portionGrams) + ' g']))
        : node('p', 'Food details will appear when analysis is complete.'));
    fillEditor(); applyMode();
}
async function delivery() {
    $('#delivery').textContent = 'Loading delivery status...';
    try {
        const result = await api(path + '/delivery');
        const labels = {WAITING_FOR_AGENT_2:'Waiting for health assessment', QUEUED:'Preparing your meal for health assessment', DELIVERED:'Ready for health assessment', DELIVERY_FAILED:'Your meal could not be delivered yet. Please check again shortly.'};
        $('#delivery').textContent = labels[result.state] || 'Checking meal delivery...';
    } catch (error) { $('#delivery').textContent = error.message; }
}
async function load() {
    render(await api(path));
    if (actions(current).validated) await delivery();
}
async function work(operation) {
    if (busy) return;
    busy = true;
    for (const button of document.querySelectorAll('main button, #refresh')) button.disabled = true;
    $('#edit-fields').disabled = true;
    $('#message').textContent = 'Processing. Please wait...';
    try { await operation(); $('#message').textContent = ''; }
    catch (error) {
        if (error.status === 409) { try { await load(); } catch { /* Preserve the original error. */ } }
        $('#message').textContent = error.message;
    } finally {
        busy = false;
        for (const button of document.querySelectorAll('main button, #refresh')) button.disabled = false;
        $('#edit-fields').disabled = false;
        $('#add-food').disabled = $('#edit-rows').children.length >= 20;
        updateReviewState();
    }
}
$('#edit-form').addEventListener('input', markDirty);
$('#add-food').addEventListener('click', () => {
    if ($('#edit-rows').children.length < 20) { editorRow(); markDirty(); }
    $('#add-food').disabled = $('#edit-rows').children.length >= 20;
});
$('#edit-foods').addEventListener('click', () => { editing = true; fillEditor(); applyMode(); $('#edit-rows input')?.focus(); });
$('#cancel-edit').addEventListener('click', () => { if (!dirty || confirm('Discard unsaved food changes?')) { editing = false; fillEditor(); applyMode(); } });
$('#refresh').addEventListener('click', () => { if (!dirty || confirm('Refreshing will discard unsaved changes. Continue?')) work(load); });
for (const action of ['confirm','retry','abandon']) $('#' + action).addEventListener('click', () => {
    if (dirty && action !== 'abandon') { $('#message').textContent = 'Save or discard your food changes first.'; return; }
    if (action === 'abandon' && !confirm('Abandon this record? The photo will be removed and analysis cannot continue.')) return;
    if (action === 'confirm' && !confirm('Confirm this meal with the foods, portions and nutrition totals shown?')) return;
    work(async () => {
        render(await api(path + '/' + action, {method:'POST'}));
        if (actions(current).validated) await delivery();
    });
});
$('#edit-form').addEventListener('submit', event => {
    event.preventDefault();
    const rows = [...$('#edit-rows').children];
    if (!rows.length) { $('#message').textContent = 'Keep at least one food item.'; return; }
    const foods = rows.map(row => {
        const name = row.querySelector('[name=name]').value.trim();
        const original = row.foodInput, sameName = name === original.name;
        return {name, searchTerm: sameName && original.searchTerm ? original.searchTerm : name,
            portionGrams: Number(row.querySelector('[name=portionGrams]').value),
            confidence: sameName ? original.confidence ?? 0 : 0, fdcId: sameName ? original.fdcId ?? null : null};
    });
    if (foods.some(food => !food.name || !Number.isFinite(food.portionGrams) || food.portionGrams < 0.1 || food.portionGrams > 10000)) {
        $('#message').textContent = 'Enter a food name and a portion between 0.1 and 10000 grams for each food.'; return;
    }
    work(async () => { render(await api(path + '/foods', {method:'PUT', body:{foods}})); });
});
$('#refresh-delivery').addEventListener('click', () => work(delivery));
window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
try {
    await requireUser();
    if (!uuid(id)) throw new Error('Invalid record ID. Select a record from My meal records.');
    await work(load);
} catch (error) { $('#message').textContent = error.message; }
