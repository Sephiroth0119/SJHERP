import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const balance = (overrides = {}) => ({
  warehouseId: 5,
  warehouseCode: 'WH-1',
  warehouseName: '主仓',
  productId: 11,
  productCode: 'P-1',
  productName: '螺栓',
  quantity: '123456789012.123456',
  costAmount: '999999999999.99',
  unitCost: '8.123456',
  ...overrides,
});

const transaction = (overrides = {}) => ({
  id: 91,
  txnType: 'OPENING',
  quantity: '10.000000',
  unitCost: '8.5',
  totalCost: '85.00',
  balanceQuantityAfter: '10.000000',
  balanceAmountAfter: '85.00',
  srcDocType: 'OPENING',
  srcDocNo: 'OP-202610-0001',
  srcLineNo: 1,
  operator: 'warehouse',
  createdAt: '2026-10-12T05:00:00Z',
  ...overrides,
});

test('balance and transaction queries send exact filters and preserve decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      if (String(input).startsWith('/api/inventory/balances')) {
        return response({
          items: [balance({ quantity: 12.5, costAmount: 100, unitCost: null })],
          total: 1,
          page: 2,
          size: 20,
        });
      }
      return response({
        items: [transaction({ quantity: 12.5, unitCost: null, totalCost: 0 })],
        total: 1,
        page: 4,
        size: 20,
      });
    };
    const api = await import('../src/api/inventoryApi.ts');
    const page = await api.searchBalances(5, 11, '螺栓', 2);
    const txns = await api.searchTransactions(5, 11, 4);

    assert.equal(
      calls[0][0],
      '/api/inventory/balances?page=2&size=20&warehouseId=5&productId=11&keyword=%E8%9E%BA%E6%A0%93',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(
      calls[1][0],
      '/api/inventory/transactions?warehouseId=5&productId=11&page=4&size=20',
    );
    assert.equal(page.items[0].quantity, '12.5');
    assert.equal(page.items[0].costAmount, '100');
    assert.equal(page.items[0].unitCost, null);
    assert.equal(txns.items[0].quantity, '12.5');
    assert.equal(txns.items[0].unitCost, null);
    assert.equal(txns.items[0].totalCost, '0');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('adjustment create sends only the narrow OPENING or COST_ADJUST body', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response({
        transactionId: 9,
        warehouseId: 5,
        productId: 11,
        txnType: 'OPENING',
        quantity: 10,
        unitCost: 8.5,
        totalCost: 85,
        balanceQuantityAfter: 10,
        balanceAmountAfter: 85,
        srcDocType: 'OPENING',
        srcDocNo: 'OP-202610-0001',
        idempotencyKey: 'OPENING:OP-202610-0001:1',
      }, 201);
    };
    const api = await import('../src/api/inventoryApi.ts');
    const opening = await api.createAdjustment({
      type: 'OPENING',
      warehouseId: 5,
      productId: 11,
      quantity: '10.000000',
      unitCost: '8.500000',
      adjustAmount: '-999.99',
      extra: true,
    });
    const cost = await api.createAdjustment({
      type: 'COST_ADJUST',
      warehouseId: 5,
      productId: 11,
      quantity: '10',
      unitCost: '8.5',
      adjustAmount: '-12.50',
    });

    assert.equal(calls[0][0], '/api/inventory/adjustments');
    assert.deepEqual(JSON.parse(calls[0][1].body), {
      type: 'OPENING',
      warehouseId: 5,
      productId: 11,
      quantity: '10.000000',
      unitCost: '8.500000',
    });
    assert.deepEqual(JSON.parse(calls[1][1].body), {
      type: 'COST_ADJUST',
      warehouseId: 5,
      productId: 11,
      adjustAmount: '-12.50',
    });
    assert.equal(opening.quantity, '10');
    assert.equal(opening.unitCost, '8.5');
    assert.equal(cost.balanceAmountAfter, '85');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('adjustment validation is string based and rejects zero overflow and missing refs', async () => {
  const api = await import('../src/api/inventoryApi.ts');
  const opening = {
    type: 'OPENING',
    warehouseId: 5,
    productId: 11,
    quantity: '10.000000',
    unitCost: '0',
    adjustAmount: '',
  };
  const cost = {
    type: 'COST_ADJUST',
    warehouseId: 5,
    productId: 11,
    quantity: '',
    unitCost: '',
    adjustAmount: '-12.50',
  };
  assert.equal(api.validateAdjustmentForm(opening), null);
  assert.equal(api.validateAdjustmentForm(cost), null);
  assert.match(api.validateAdjustmentForm({ ...opening, warehouseId: 0 }), /仓库/);
  assert.match(api.validateAdjustmentForm({ ...opening, productId: 0 }), /商品/);
  assert.match(api.validateAdjustmentForm({ ...opening, quantity: '0' }), /期初数量必须大于 0/);
  assert.match(api.validateAdjustmentForm({ ...opening, unitCost: '-1' }), /期初单价必须 ≥ 0/);
  assert.match(api.validateAdjustmentForm({ ...cost, adjustAmount: '0.00' }), /成本调整额不能为 0/);
  assert.match(api.validateAdjustmentForm({ ...cost, adjustAmount: '1.001' }), /小数最多 2 位/);
});

test('ledger client and workbench expose no fabricated cancel edit delete or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/inventoryApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/InventoryLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockBalance|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:quantity|costAmount|unitCost|adjustAmount)/);
});

test('ledger tab is open to inventory module users and adjust is fail-closed', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('inventory:adjust'\)/);
  assert.equal(appSource.includes('>台账</button>'), true);
  assert.equal(appSource.includes('<InventoryLedgerWorkbench canAdjust={canAdjust} />'), true);
  assert.doesNotMatch(appSource, /Role\.WAREHOUSE|roles\.includes/);
});

test('ledger workbench has in-flight guard confirmation and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/InventoryLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /期初建账/);
  assert.match(source, /成本调整/);
  assert.match(source, /inventory:adjust/);
  for (const token of [
    'listVersion',
    'txnVersion',
    'warehouseVersion',
    'productVersion',
  ]) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /仓库 #\$\{id\}（名称不可用）/);
  assert.match(source, /商品 #\$\{id\}（名称不可用）/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
});
