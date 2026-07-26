import test from 'node:test';
import assert from 'node:assert/strict';

const response = (body) => ({ ok: true, status: 200, json: async () => body });
const storage = { getItem: () => null, setItem() {}, removeItem() {} };

test('catalog query/detail normalize rates and safe mutations never DELETE', async () => {
  const oldFetch = globalThis.fetch;
  const oldStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      const product = { id: 1, code: 'P', name: '商品', inventoryCategory: 'MERCHANDISE', baseUnitId: 2, unitConversions: [{ unitId: 3, rate: 12.5 }] };
      if (String(input).includes('/catalog/categories') && !init.method) return response([]);
      if (String(input).includes('/catalog/units') && !init.method) return response([]);
      return String(input).includes('/1') ? response(product) : response({ items: [product], total: 1, page: 2, size: 20 });
    };
    const api = await import('../src/api/catalogApi.ts');
    const page = await api.searchProducts(' 商品 ', 'ENABLED', 2);
    const detail = await api.getProduct(1);
    const disabled = await api.setProductStatus(1, 'DISABLED');
    const enabled = await api.setProductStatus(1, 'ENABLED');
    assert.equal(disabled.unitConversions[0].rate, '12.5');
    assert.equal(enabled.unitConversions[0].rate, '12.5');
    await api.listCategories();
    await api.listUnits();
    await api.createCategory({ name: '五金', parentId: 4 });
    await api.renameCategory(2, '紧固件');
    await api.createUnit({ name: '箱', precision: 0 });
    await api.updateUnit(3, { name: '盒', precision: 2 });
    assert.equal(page.items[0].unitConversions[0].rate, '12.5');
    assert.equal(detail.unitConversions[0].rate, '12.5');
    assert.match(calls[0][0], /products\?page=2&size=20&keyword=%E5%95%86%E5%93%81&status=ENABLED/);
    assert.match(calls[2][0], /\/api\/catalog\/products\/1\/disable$/);
    assert.match(calls[3][0], /\/api\/catalog\/products\/1\/enable$/);
    assert.match(calls[4][0], /\/api\/catalog\/categories$/);
    assert.match(calls[5][0], /\/api\/catalog\/units$/);
    assert.deepEqual(calls.slice(1).map(([, init]) => init.method ?? 'GET'), ['GET', 'POST', 'POST', 'GET', 'GET', 'POST', 'PUT', 'POST', 'PUT']);
    assert.deepEqual(JSON.parse(calls[6][1].body), { name: '五金', parentId: 4 });
    assert.deepEqual(JSON.parse(calls[7][1].body), { name: '紧固件' });
    assert.deepEqual(JSON.parse(calls[8][1].body), { name: '箱', precision: 0 });
    assert.deepEqual(JSON.parse(calls[9][1].body), { name: '盒', precision: 2 });
    assert.equal(calls.every(([, init]) => (init.method ?? 'GET') !== 'DELETE'), true);
  } finally { globalThis.fetch = oldFetch; globalThis.localStorage = oldStorage; }
});

test('rate boundaries match the backend decimal contract', async () => {
  const { ratePattern, validateConversions } = await import('../src/api/catalogApi.ts');
  for (const value of ['0.1', '1', '1.5', '999999999999.999999']) assert.equal(ratePattern.test(value), true, value);
  for (const value of ['0', '0.0', '-1', '1000000000000', '1.1234567']) assert.equal(ratePattern.test(value), false, value);
  assert.equal(validateConversions(null, []), '请选择基本单位');
  assert.equal(
    validateConversions(1, [{ unitId: 1, rate: '1' }]),
    '基本单位无需登记换算率',
  );
  assert.equal(
    validateConversions(1, [
      { unitId: 2, rate: '1.5' },
      { unitId: 2, rate: '2' },
    ]),
    '同一换算单位不可重复登记',
  );
  assert.equal(
    validateConversions(1, [{ unitId: 2, rate: '0' }]),
    '换算率必须大于 0，整数最多 12 位、小数最多 6 位',
  );
  assert.equal(
    validateConversions(1, [{ unitId: 2, rate: '1.5' }]),
    null,
  );
});

test('product create/update narrow extra fields and preserve rate strings', async () => {
  const oldFetch = globalThis.fetch;
  const oldStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => { calls.push([String(input), init]); return response({ unitConversions: [{ unitId: 2, rate: 12.5 }] }); };
    const api = await import('../src/api/catalogApi.ts');
    const form = { code: 'P', name: '商品', spec: '', categoryId: null, inventoryCategory: 'MERCHANDISE', baseUnitId: 1, barcode: '', remark: '', unitConversions: [{ unitId: 2, rate: '12.500000' }], id: 99, status: 'DISABLED', createdAt: 'secret' };
    const created = await api.createProduct(form);
    const updated = await api.updateProduct(1, form);
    assert.equal(created.unitConversions[0].rate, '12.5');
    assert.equal(updated.unitConversions[0].rate, '12.5');
    for (const [, init] of calls) {
      assert.deepEqual(Object.keys(JSON.parse(init.body)).sort(), ['barcode', 'baseUnitId', 'categoryId', 'code', 'inventoryCategory', 'name', 'remark', 'spec', 'unitConversions']);
      assert.equal(JSON.parse(init.body).unitConversions[0].rate, '12.500000');
    }
    assert.deepEqual(calls.map(([, init]) => init.method), ['POST', 'PUT']);
  } finally { globalThis.fetch = oldFetch; globalThis.localStorage = oldStorage; }
});
