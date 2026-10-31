import {api} from './api.js';
import {requireUser, detailUrl, uuid} from './dietary-ui.js';
import {photoDigest, newRecordId} from './upload-crypto.js';
import {prepareMealPhoto} from './upload-photo.js';
const form = document.querySelector('#upload-form'), fields = document.querySelector('#upload-fields');
const message = document.querySelector('#message'), photo = document.querySelector('#photo');
const preview = document.querySelector('#preview');
const description = document.querySelector('#meal-description'), removePhoto = document.querySelector('#remove-photo');
let user, busy = false, previewUrl;
const localNow = new Date();
document.querySelector('#meal-time').value = new Date(localNow - localNow.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
const key = () => 'nutrimarket.upload.' + user.id;
function pending(value) {
    document.querySelector('#pending').hidden = !value;
    if (value) document.querySelector('#pending-link').href = detailUrl(value.id);
}
async function syncProfileVersion() {
    let version = 1;
    try {
        version = (await api('/api/v1/health/profile')).version;
    } catch (error) {
        if (error.code !== 'PROFILE_REQUIRED') throw error;
    }
    if (!Number.isSafeInteger(version) || version < 1) throw new Error('Unable to load your health profile. Reload the page.');
    return version;
}
function showPreview() {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    preview.hidden = true;
    const file = photo.files[0];
    removePhoto.hidden = !file;
    if (file && ['image/jpeg', 'image/png'].includes(file.type) && file.size <= 30 * 1024 * 1024) {
        previewUrl = URL.createObjectURL(file); preview.src = previewUrl; preview.hidden = false;
    }
    message.textContent = '';
}
photo.addEventListener('change', showPreview);
removePhoto.addEventListener('click', () => { photo.value = ''; showPreview(); });
form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !user) return;
    busy = true;
    const input = new FormData(form);
    fields.disabled = true;
    try {
        const selected = input.get('photo'), file = selected?.size ? selected : null;
        const mealDescription = String(input.get('mealDescription') || '').trim();
        if (!file && !mealDescription) throw new Error('Add a meal photo or describe what you ate.');
        if (mealDescription.length > 3000) throw new Error('The meal description must not exceed 3000 characters.');
        const version = await syncProfileVersion();
        const date = new Date(input.get('mealTime'));
        if (!Number.isFinite(date.getTime()) || date.getTime() > Date.now() + 300000) throw new Error('Enter a valid meal time, no more than 5 minutes in the future.');
        if (!input.get('consent')) throw new Error('Please consent to meal analysis first.');
        message.textContent = file ? 'Preparing the photo...' : 'Preparing your meal description...';
        const preparedPhoto = file ? await prepareMealPhoto(file) : null;
        const hash = file ? await photoDigest(await file.arrayBuffer()) : '';
        // Preserve photo-only draft signatures while including text in all new mixed/text requests.
        const signatureFor = profileVersion => JSON.stringify(mealDescription
            ? [user.id, profileVersion, date.toISOString(), hash, mealDescription]
            : [user.id, profileVersion, date.toISOString(), hash]);
        const signature = signatureFor(version);
        let previous;
        try { previous = JSON.parse(sessionStorage.getItem(key())); } catch { previous = null; }
        // Resolve any uncertain earlier upload before sending it again, including after image resizing.
        if (previous && uuid(previous.id) && previous.signature === signatureFor(previous.version)) {
            try {
                const existing = await api('/api/v1/dietary-records/' + encodeURIComponent(previous.id));
                sessionStorage.removeItem(key());
                location.assign(detailUrl(existing.recordId));
                return;
            } catch (error) {
                if (error.status !== 404) throw error;
            }
        }
        const id = previous?.signature === signature && uuid(previous.id) ? previous.id : newRecordId();
        const draft = {id, signature, mealTime: input.get('mealTime'), version, mealDescription};
        sessionStorage.setItem(key(), JSON.stringify(draft)); pending(draft);
        const body = new FormData();
        body.set('recordId', id); body.set('profileVersion', String(version)); body.set('mealTime', date.toISOString());
        body.set('photoProcessingConsent', 'true');
        if (preparedPhoto) body.set('photo', preparedPhoto);
        if (mealDescription) body.set('mealDescription', mealDescription);
        message.textContent = 'Analysing your meal. Please wait without submitting again...';
        const record = await api('/api/v1/dietary-records', {method: 'POST', body});
        sessionStorage.removeItem(key());
        location.assign(detailUrl(record.recordId));
    } catch (error) {
        message.textContent = error.message;
        if (error.code === 'PROFILE_VERSION_CONFLICT') {
            // This response confirms that no new record was created.
            sessionStorage.removeItem(key()); pending(null);
            try {
                await syncProfileVersion();
                message.textContent = 'Your health profile changed during upload. Your details have been refreshed. Submit again to continue.';
            } catch (refreshError) { message.textContent = refreshError.message; }
        }
    } finally { fields.disabled = false; busy = false; }
});
try {
    user = await requireUser(); message.textContent = '';
    const version = await syncProfileVersion();
    let draft;
    try { draft = JSON.parse(sessionStorage.getItem(key())); } catch { draft = null; }
    if (draft && uuid(draft.id)) {
        pending(draft);
        form.elements.mealTime.value = draft.mealTime;
        description.value = draft.mealDescription || '';
        if (draft.version !== version) message.textContent = 'Your health profile has changed since your previous upload. Check the previous record before submitting again.';
    }
    form.hidden = false;
} catch (error) { message.textContent = error.message; }
