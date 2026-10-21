import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

test('trial balance and account balance keep amounts as strings and hit real GL endpoints', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('account-balance')
        ? response({
          accountCode: '1001',
          totalDebit: 12.5,
          totalCredit: '0.00',
          netBalance: 12.5,
        })
        : response({
          period: '202610',
          balances: [{
            accountCode: '1001',
            totalDebit: 12.5,
            totalCredit: '8.00',
            netBalance: '4.50',
          }],
          totalDebit: 12.5,
          totalCredit: '8.00',
        });
    };
    const api = await import('../src/api/glBalanceApi.ts');
    const trial = await api.getTrialBalance(' 202610 ');
    const account = await api.getAccountBalance('1001', '202610');

    assert.equal(calls[0][0], '/api/gl/trial-balance?period=202610');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/gl/account-balance?accountCode=1001&period=202610');
    assert.equal(trial.balances[0].totalDebit, '12.5');
    assert.equal(trial.totalCredit, '8.00');
    assert.equal(account.netBalance, '12.5');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('gl balance client rejects invalid period or empty account before calling the API', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let called = 0;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async () => {
      called += 1;
      return response({});
    };
    const api = await import('../src/api/glBalanceApi.ts');
    await assert.rejects(() => api.getTrialBalance('2026'), /yyyyMM/);
    await assert.rejects(() => api.getAccountBalance('', '202610'), /科目编码/);
    await assert.rejects(() => api.getAccountBalance('1001', '202613'), /月份/);
    assert.equal(called, 0);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('gl balance client is read-only and does not use float math', () => {
  const source = readFileSync(new URL('../src/api/glBalanceApi.ts', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /method:\s*['"]POST['"]/);
  assert.doesNotMatch(source, /parseFloat\s*\(/);
  assert.match(source, /\/api\/gl\/trial-balance/);
  assert.match(source, /\/api\/gl\/account-balance/);
});
