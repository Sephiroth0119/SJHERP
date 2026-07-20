import test from 'node:test';
import assert from 'node:assert/strict';

import { isUsableToken } from '../src/security/token.ts';

function tokenWithPayload(payload) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode(payload)}.signature`;
}

test('accepts a JWT whose expiration is in the future', () => {
  const token = tokenWithPayload({ exp: 2_000 });
  assert.equal(isUsableToken(token, 1_999_000), true);
});

test('rejects expired, malformed, and non-expiring tokens', () => {
  assert.equal(isUsableToken(tokenWithPayload({ exp: 2_000 }), 2_000_000), false);
  assert.equal(isUsableToken('not-a-jwt', 1_000), false);
  assert.equal(isUsableToken(tokenWithPayload({}), 1_000), false);
});
