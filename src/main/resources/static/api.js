const TOKEN_KEY = 'nutrimarket.accessToken';

export function saveToken(token) {
    sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
    sessionStorage.removeItem(TOKEN_KEY);
}

export function logout(reason = '') {
    clearToken();
    // Expert review pages return to the expert sign-in entry.
    const params = new URLSearchParams();
    if (location.pathname.endsWith('expert.html')) params.set('role', 'expert');
    if (reason) params.set('reason', reason);
    const query = params.toString();
    location.replace('/login.html' + (query ? '?' + query : ''));
}

export async function api(path, {method = 'GET', body, authenticated = true} = {}) {
    const headers = new Headers();
    const multipart = body instanceof FormData;
    if (body !== undefined && !multipart) headers.set('Content-Type', 'application/json');
    if (authenticated) {
        const token = sessionStorage.getItem(TOKEN_KEY);
        if (!token) {
            logout('required');
            throw new Error('Please sign in first.');
        }
        headers.set('Authorization', 'Bearer ' + token);
    }
    let response;
    try {
        response = await fetch(path, {
            method, headers, cache: 'no-store', credentials: 'omit',
            body: body === undefined ? undefined : multipart ? body : JSON.stringify(body)
        });
    } catch {
        throw new Error('Unable to connect to the server. Check your connection and try again.');
    }
    const data = await response.json().catch(() => null);
    if (response.status === 401 && authenticated) {
        logout('expired');
        throw new Error('Your session has expired. Please sign in again.');
    }
    if (!response.ok) {
        const error = new Error(data?.message || 'Request failed. Please try again later.');
        error.status = response.status; error.code = data?.code; throw error;
    }
    if (data === null) throw new Error('The server returned an unexpected response format. Please try again later.');
    return data;
}
