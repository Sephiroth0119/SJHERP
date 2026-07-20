import test from 'node:test';
import assert from 'node:assert/strict';

import { getToken, setToken } from '../src/api/http.ts';

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

  assert.equal(getToken(), null);
  assert.equal(globalThis.localStorage.getItem('sjherp.auth.token'), null);
});
