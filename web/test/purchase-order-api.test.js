import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const order = (overrides = {}) => ({
  docNo: 'PO-202607-0001',
  supplierId: 7,
  orderDate: '2026-07-26',
  remark: '加急',
  status: 'DRAFT',
  totalAmount: '999999999999.99',
  lines: [
    {
      lineNo: 1,
      productId: 11,
      quantity: '123456789012.123456',
      unitPrice: '0.000001',
      amount: '123456.78',
      receivedQty: '0.000000',
      outstandingQty: '123456789012.123456',
    },
  ],
  ...overrides,
});

test('purchase order list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('PO-202607-0001')
        ? response(order())
        : response({
            items: [order({ totalAmount: 999999999999.99 })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/purchaseOrderApi.ts');
    const page = await api.searchPurchaseOrders(7, 'APPROVED', 3);
    const detail = await api.getPurchaseOrder('PO-202607-0001');

    assert.equal(
      calls[0][0],
      '/api/purchase/orders?page=3&size=20&supplierId=7&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/purchase/orders/PO-202607-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].totalAmount, '999999999999.99');
    assert.equal(detail.lines[0].quantity, '123456789012.123456');
    assert.equal(detail.lines[0].unitPrice, '0.000001');
    assert.equal(detail.lines[0].amount, '123456.78');
    assert.equal(detail.lines[0].receivedQty, '0.000000');
    assert.equal(detail.lines[0].outstandingQty, '123456789012.123456');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('purchase order create sends the exact narrow body and keeps input strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(order(), 201);
    };
    const api = await import('../src/api/purchaseOrderApi.ts');
    await api.createPurchaseOrder({
      supplierId: 7,
      orderDate: '2026-07-26',
      remark: '保留字符串',
      status: 'APPROVED',
      totalAmount: 'do-not-send',
      lines: [
        {
          productId: 11,
          quantity: '999999999999.999999',
          unitPrice: '0.000001',
          amount: 'do-not-send',
          receivedQty: 'do-not-send',
          lineNo: 99,
        },
      ],
      audit: 'do-not-send',
    });

    assert.equal(call[0], '/api/purchase/orders');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      supplierId: 7,
      orderDate: '2026-07-26',
      remark: '保留字符串',
      lines: [
        {
          productId: 11,
          quantity: '999999999999.999999',
          unitPrice: '0.000001',
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('approve and close use only the real POST action URLs and never DELETE', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(
        order({
          totalAmount: 12.5,
          lines: [
            {
              lineNo: 1,
              productId: 11,
              quantity: 2.5,
              unitPrice: 5,
              amount: 12.5,
              receivedQty: 1,
              outstandingQty: 1.5,
            },
          ],
        }),
      );
    };
    const api = await import('../src/api/purchaseOrderApi.ts');
    const approved = await api.approvePurchaseOrder('PO/unsafe');
    const closed = await api.closePurchaseOrder('PO/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/purchase/orders/PO%2Funsafe/approve', 'POST'],
        ['/api/purchase/orders/PO%2Funsafe/close', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
    for (const result of [approved, closed]) {
      assert.equal(result.totalAmount, '12.5');
      assert.equal(result.lines[0].quantity, '2.5');
      assert.equal(result.lines[0].unitPrice, '5');
      assert.equal(result.lines[0].amount, '12.5');
      assert.equal(result.lines[0].receivedQty, '1');
      assert.equal(result.lines[0].outstandingQty, '1.5');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('purchase order validation matches quantity and unit-price domain boundaries', async () => {
  const api = await import('../src/api/purchaseOrderApi.ts');
  const valid = {
    supplierId: 7,
    orderDate: '2026-07-26',
    remark: '',
    lines: [
      {
        productId: 11,
        quantity: '999999999999.999999',
        unitPrice: '0',
      },
    ],
  };
  assert.equal(api.validatePurchaseOrderForm(valid), null);
  assert.match(
    api.validatePurchaseOrderForm({ ...valid, lines: [] }),
    /至少要有一行/,
  );
  assert.match(
    api.validatePurchaseOrderForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '0' }],
    }),
    /数量必须大于 0/,
  );
  assert.match(
    api.validatePurchaseOrderForm({
      ...valid,
      lines: [{ ...valid.lines[0], unitPrice: '-0.01' }],
    }),
    /单价必须为非负数/,
  );
  assert.match(
    api.validatePurchaseOrderForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], quantity: '1' }],
    }),
    /商品不能重复/,
  );
});

test('purchase order client and workbench expose no fabricated mutation action', () => {
  const apiSource = readFileSync(
    new URL('../src/api/purchaseOrderApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/PurchaseOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel|reverse)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废|冲销)\s*</);
  assert.doesNotMatch(`${apiSource}\n${workbenchSource}`, /parseFloat\s*\(/);
  assert.doesNotMatch(
    `${apiSource}\n${workbenchSource}`,
    /Number\s*\([^)]*(?:quantity|unitPrice|amount)/,
  );
});

test('purchase order tab is fail-closed on the exact permission code', () => {
  const appSource = readFileSync(
    new URL('../src/App.tsx', import.meta.url),
    'utf8',
  );
  assert.match(
    appSource,
    /const canUseOrders = permissions\.includes\('purchase:order'\)/,
  );
  assert.equal(
    appSource.includes(
      '{canUseOrders && <button type="button" role="tab"',
    ),
    true,
  );
  assert.equal(appSource.includes('>采购订单</button>}'), true);
});

test('create and status transitions share a synchronous in-flight guard', () => {
  const source = readFileSync(
    new URL('../src/components/PurchaseOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const saveSource = source.slice(
    source.indexOf('const save = async'),
    source.indexOf("const transition = async"),
  );
  const transitionSource = source.slice(
    source.indexOf("const transition = async"),
    source.indexOf('const supplierName ='),
  );
  for (const mutationSource of [saveSource, transitionSource]) {
    assert.match(mutationSource, /mutationInFlight\.current/);
    assert.match(mutationSource, /mutationInFlight\.current = true/);
    assert.match(mutationSource, /finally \{/);
    assert.match(mutationSource, /mutationInFlight\.current = false/);
  }
});
