import {statuses, time as formatTime, detailUrl} from './dietary-ui.js';
import {api, logout} from './api.js';

const message = document.querySelector('#message');
const previous = document.querySelector('#previous');
const next = document.querySelector('#next');
const refresh = document.querySelector('#refresh');
let page = 1;
let busy = false;
const size = 10;
const filter = document.querySelector('#status-filter');
filter.addEventListener('change', () => loadRecords(1));
document.querySelector('#logout').addEventListener('click', () => logout());
refresh.addEventListener('click', () => loadRecords(1));
previous.addEventListener('click', () => loadRecords(page - 1));
next.addEventListener('click', () => loadRecords(page + 1));

async function loadRecords(requestedPage) {
    if (busy) return;
    busy = true;
    refresh.disabled = previous.disabled = next.disabled = filter.disabled = true;
    document.querySelector('#records').hidden = true;
    document.querySelector('#empty-state').hidden = true;
    document.querySelector('#record-rows').replaceChildren();
    document.querySelector('#page-info').textContent = '';
    message.textContent = 'Loading records...';
    try {
        const result = await api(`/api/v1/dietary-records?page=${requestedPage}&size=${size}${filter.value ? "&status=" + encodeURIComponent(filter.value) : ""}`);
        page = result.page;
        const rows = document.querySelector('#record-rows');
        for (const record of result.items) {
            const row = document.createElement('tr');
            for (const value of [record.recordId, formatTime(record.mealTime),
                statuses[record.status] || record.status, formatTime(record.createdAt)]) {
                const cell = document.createElement('td');
                if (value === record.recordId) {
                    const link = document.createElement('a'); link.href = detailUrl(record.recordId); link.textContent = "Meal · " + value.slice(0, 8); link.title = value; cell.append(link);
                } else if (value === (statuses[record.status] || record.status)) {
                    const badge = document.createElement('span'); badge.className = 'status-pill'; badge.dataset.state = record.status; badge.textContent = value; cell.append(badge);
                } else cell.textContent = value;
                row.append(cell);
            }
            rows.append(row);
        }
        document.querySelector('#records').hidden = result.items.length === 0;
        message.textContent = '';
        document.querySelector('#record-count').textContent = result.total;
        document.querySelector('#empty-state').hidden = result.items.length !== 0;
        document.querySelector('#empty-title').textContent = filter.value ? 'No records match this status' : 'Start with a meal photo';
        document.querySelector('#page-info').textContent = `Page ${page}; total: ${result.total} records`;
        previous.disabled = page <= 1;
        next.disabled = page * size >= result.total;
    } catch (error) {
        message.textContent = error.message;
    } finally {
        busy = false;
        refresh.disabled = filter.disabled = false;
    }
}

try {
    const user = await api('/api/v1/auth/me');
    document.querySelector('#username').textContent = user.username;
    document.querySelector('#created-at').textContent = formatTime(user.createdAt);
    document.querySelector('#loading').hidden = true;
    document.querySelector('#home').hidden = false;
    import('./questionnaire.js').then(m => m.offerQuestionnaire()).catch(() => {});
    await loadRecords(1);
} catch (error) {
    document.querySelector('#loading').textContent = error.message + ' Refresh the page and try again.';
}
