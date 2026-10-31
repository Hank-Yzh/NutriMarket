import {api, saveToken, clearToken} from './api.js';

const registering = document.body.dataset.page === 'register';
const form = document.querySelector('#auth-form');
const submit = document.querySelector('#submit');
const message = document.querySelector('#message');
const reason = new URLSearchParams(location.search).get('reason');
const expertMode = !registering && new URLSearchParams(location.search).get('role') === 'expert';
if (expertMode) {
    document.title = 'Human expert sign-in - NutriMarket';
    document.querySelector('.auth-card .eyebrow').textContent = 'HUMAN EXPERT';
    document.querySelector('.auth-card h1').textContent = 'Expert sign-in';
    document.querySelector('.auth-card .intro').textContent = 'Review health assessments that need a human decision before automated planning.';
    document.querySelector('.auth-switch').hidden = true;
    const link = document.querySelector('#role-link');
    link.textContent = '← Member sign-in';
    link.href = '/login.html';
}
if (reason === 'registered') message.textContent = 'Registration successful. Sign in with your new account.';
if (reason === 'required') message.textContent = 'Please sign in to access your dashboard.';
if (reason === 'expired') message.textContent = 'Your session has expired. Please sign in again.';

form.addEventListener('submit', async event => {
    event.preventDefault();
    if (submit.disabled) return;
    message.textContent = '';
    const values = new FormData(form);
    const username = values.get('username').trim().toLowerCase();
    const password = values.get('password');
    if (!/^[a-z][a-z0-9_]{2,31}$/.test(username)) {
        message.textContent = 'The username must contain 3–32 letters, digits or underscores and start with a letter.';
        return;
    }
    if (new TextEncoder().encode(password).length > 72) {
        message.textContent = 'The password must not exceed 72 UTF-8 bytes.';
        return;
    }
    if (registering && password !== values.get('confirmPassword')) {
        message.textContent = 'The passwords do not match.';
        return;
    }
    submit.disabled = true;
    message.textContent = registering ? 'Creating your account...' : 'Signing in...';
    try {
        const result = await api('/api/v1/auth/' + (registering ? 'register' : 'login'), {
            method: 'POST', body: {username, password}, authenticated: false
        });
        form.reset();
        if (registering) {
            location.replace('/login.html?reason=registered');
        } else {
            saveToken(result.accessToken);
            if (expertMode) {
                const access = await api('/api/v1/health/expert/access');
                if (!access.expert) {
                    clearToken();
                    throw new Error('This account does not have expert review access. Ask an administrator to add it to the expert list.');
                }
            }
            location.replace(expertMode ? '/expert.html' : '/');
        }
    } catch (error) {
        message.textContent = error.message;
        submit.disabled = false;
    }
});
