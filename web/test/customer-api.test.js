import test from 'node:test';
import assert from 'node:assert/strict';

const customer = (creditLimit) => ({ id: 1, code: 'CUS-1', name: '客户', contactPerson: null, contactPhone: null, address: null, taxNo: null, settlementMethod: 'MONTHLY', creditLimit, currency: 'CNY', status: 'ENABLED', createdAt: '', updatedAt: '' });

test('customer API normalizes search numeric and null credit limits', async () => {
  const previousFetch = globalThis.fetch; const previousStorage = globalThis.localStorage;
  try {
    let url = '';
    globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
    globalThis.fetch = async (input) => { url = String(input); return { ok: true, status: 200, json: async () => ({ items: [customer(120.5), { ...customer(null), id: 2 }], total: 2, page: 1, size: 20 }) }; };
    const { searchCustomers } = await import('../src/api/customerApi.ts');
    const result = await searchCustomers(' acme ', 'ENABLED', 1);
    assert.match(url, /keyword=acme/); assert.match(url, /status=ENABLED/);
    assert.equal(result.items[0].creditLimit, '120.5'); assert.equal(result.items[1].creditLimit, '');
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage; }
});

test('customer create and update send only the narrow request body', async () => {
  const previousFetch = globalThis.fetch; const previousStorage = globalThis.localStorage;
  try {
    const bodies = []; globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
    globalThis.fetch = async (_input, init = {}) => { bodies.push(JSON.parse(init.body)); return { ok: true, status: 200, json: async () => customer('12.30') }; };
    const { createCustomer, updateCustomer } = await import('../src/api/customerApi.ts');
    const form = { code: 'C', name: '客户', contactPerson: '', contactPhone: '', address: '', taxNo: '', settlementMethod: 'MONTHLY', creditLimit: '12.30' };
    await createCustomer({ ...form, creditLimit: '' }); await updateCustomer(1, form);
    assert.equal(bodies[0].creditLimit, null); assert.equal(bodies[1].creditLimit, '12.30');
    assert.deepEqual(Object.keys(bodies[1]).sort(), ['address', 'code', 'contactPerson', 'contactPhone', 'creditLimit', 'name', 'settlementMethod', 'taxNo']);
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage; }
});

test('customer status action uses the real endpoint', async () => {
  const previousFetch = globalThis.fetch; const previousStorage = globalThis.localStorage;
  try {
    let url = ''; globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
    globalThis.fetch = async (input) => { url = String(input); return { ok: true, status: 200, json: async () => customer(null) }; };
    const { setCustomerStatus } = await import('../src/api/customerApi.ts'); await setCustomerStatus(1, 'DISABLED');
    assert.equal(url, '/api/partner/customers/1/disable');
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage; }
});
