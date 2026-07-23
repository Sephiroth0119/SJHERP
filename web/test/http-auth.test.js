import test from 'node:test';
import assert from 'node:assert/strict';

import {
  clearAuth,
  getStoredUser,
  getToken,
  request,
  setToken,
  setUnauthorizedHandler,
} from '../src/api/http.ts';

class MemoryStorage {
  #values = new Map();

  getItem(key) {
    return this.#values.get(key) ?? null;
  }

  setItem(key, value) {
    this.#values.set(key, String(value));
  }

  removeItem(key) {
    this.#values.delete(key);
  }
}

function tokenWithExpiration(exp) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'HS256' })}.${encode({ exp })}.signature`;
}

test('expired stored tokens are not returned to API callers', () => {
  globalThis.localStorage = new MemoryStorage();
  setToken(tokenWithExpiration(2_000));
  globalThis.localStorage.setItem('sjherp.chat.sessionId', 'old-session');

  assert.equal(getToken(), null);
  assert.equal(globalThis.localStorage.getItem('sjherp.auth.token'), null);
  assert.equal(globalThis.localStorage.getItem('sjherp.chat.sessionId'), null);
});

test('manual session termination clears auth and chat state together', () => {
  globalThis.localStorage = new MemoryStorage();
  globalThis.localStorage.setItem('sjherp.auth.token', tokenWithExpiration(2_000_000_000));
  globalThis.localStorage.setItem('sjherp.auth.user', '{}');
  globalThis.localStorage.setItem('sjherp.chat.sessionId', 'old-session');

  clearAuth();

  assert.equal(globalThis.localStorage.getItem('sjherp.auth.token'), null);
  assert.equal(globalThis.localStorage.getItem('sjherp.auth.user'), null);
  assert.equal(globalThis.localStorage.getItem('sjherp.chat.sessionId'), null);
});

test('401 handling uses the same boundary and clears the chat session', async () => {
  globalThis.localStorage = new MemoryStorage();
  globalThis.localStorage.setItem('sjherp.auth.token', tokenWithExpiration(2_000_000_000));
  globalThis.localStorage.setItem('sjherp.chat.sessionId', 'old-session');
  const originalFetch = globalThis.fetch;
  let notified = 0;
  globalThis.fetch = async () => ({
    ok: false,
    status: 401,
    json: async () => ({ error: 'expired' }),
  });
  setUnauthorizedHandler(() => { notified += 1; });

  await assert.rejects(() => request('/api/private'), { status: 401 });

  setUnauthorizedHandler(null);
  globalThis.fetch = originalFetch;
  assert.equal(notified, 1);
  assert.equal(globalThis.localStorage.getItem('sjherp.chat.sessionId'), null);
});

test('legacy cached users without permissions degrade to an empty permission set', () => {
  globalThis.localStorage = new MemoryStorage();
  globalThis.localStorage.setItem('sjherp.auth.user', JSON.stringify({
    username: 'alice',
    displayName: 'Alice',
    roles: ['SALES'],
  }));

  assert.deepEqual(getStoredUser(), {
    username: 'alice',
    displayName: 'Alice',
    roles: ['SALES'],
    permissions: [],
  });
});
