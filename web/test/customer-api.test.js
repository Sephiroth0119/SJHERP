import test from 'node:test';
import assert from 'node:assert/strict';

test('customer API normalizes numeric/null credit limits and sends a narrow request body', async () => {
  const calls = [];
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
  globalThis.fetch = async (url, init = {}) => { calls.push({ url, init }); return { ok: true, status: 200, json: async () => ({ id: 1, code: 'CUS-1', name: '客户', contactPerson: null, contactPhone: null, address: null, taxNo: null, settlementMethod: 'MONTHLY', creditLimit: 120.5, currency: 'CNY', status: 'ENABLED', createdAt: '', updatedAt: '' }) }; };
  const { createCustomer } = await import('../src/api/customerApi.ts');
  const result = await createCustomer({ code: '', name: '客户', contactPerson: '', contactPhone: '', address: '', taxNo: '', settlementMethod: 'MONTHLY', creditLimit: '' });
  assert.equal(result.creditLimit, '120.5');
  assert.deepEqual(JSON.parse(calls[0].init.body), { code: '', name: '客户', contactPerson: '', contactPhone: '', address: '', taxNo: '', settlementMethod: 'MONTHLY', creditLimit: null });
});

test('customer API status action uses the real endpoint', async () => {
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
  let url = '';
  globalThis.fetch = async (input) => { url = String(input); return { ok: true, status: 200, json: async () => ({ id: 1, code: 'C', name: 'C', contactPerson: null, contactPhone: null, address: null, taxNo: null, settlementMethod: 'CASH', creditLimit: null, currency: 'CNY', status: 'DISABLED', createdAt: '', updatedAt: '' }) }; };
  const { setCustomerStatus } = await import('../src/api/customerApi.ts');
  await setCustomerStatus(1, 'DISABLED');
  assert.equal(url, '/api/partner/customers/1/disable');
});
