import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash, randomFillSync, webcrypto} from 'node:crypto';
import {readFile} from 'node:fs/promises';

const source = await readFile(new URL('../../main/resources/static/upload-crypto.js', import.meta.url), 'utf8');
const {photoDigest, newRecordId} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
function setCrypto(value) { Object.defineProperty(globalThis, 'crypto', {value, configurable: true}); }
test.afterEach(() => {
    if (originalCrypto) Object.defineProperty(globalThis, 'crypto', originalCrypto);
    else delete globalThis.crypto;
});

test('LAN HTTP without SubtleCrypto matches SHA-256 vectors and binary image sizes', async () => {
    setCrypto({getRandomValues: bytes => randomFillSync(bytes)});
    for (const text of ['', 'abc', 'nutrition record', 'caf\u00e9', 'a'.repeat(1000000)]) {
        const input = new TextEncoder().encode(text);
        assert.equal(await photoDigest(input.buffer), createHash('sha256').update(input).digest('hex'));
    }
    for (const size of [55, 56, 63, 64, 65, 127, 128, 129, 256 * 1024, 10 * 1024 * 1024]) {
        const input = randomFillSync(new Uint8Array(size));
        assert.equal(await photoDigest(input.buffer), createHash('sha256').update(input).digest('hex'), `size ${size}`);
    }
});

test('native and fallback SHA-256 keep identical upload fingerprints', async () => {
    const input = randomFillSync(new Uint8Array(12345)).buffer;
    setCrypto(webcrypto);
    const native = await photoDigest(input);
    setCrypto(undefined);
    assert.equal(await photoDigest(input), native);
});

test('LAN HTTP without randomUUID generates distinct RFC 4122 version 4 identifiers', () => {
    setCrypto({getRandomValues: bytes => randomFillSync(bytes)});
    const ids = new Set();
    for (let i = 0; i < 1000; i++) {
        const id = newRecordId();
        assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
        ids.add(id);
    }
    assert.equal(ids.size, 1000);
});

test('unavailable random generator produces an actionable error, not a weak fallback', () => {
    setCrypto(undefined);
    assert.throws(newRecordId, /Your browser cannot generate record IDs/);
});
