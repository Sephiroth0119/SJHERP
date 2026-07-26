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
  docNo: 'SO-202607-0001',
  customerId: 7,
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
      deliveredQty: '0.000000',
      remainingQty: '123456789012.123456',
    },
  ],
  ...overrides,
});

test('sales order list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('SO-202607-0001')
        ? response(order())
        : response({
            items: [order({ totalAmount: 999999999999.99 })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/salesOrderApi.ts');
    const page = await api.searchSalesOrders(7, 'APPROVED', 3);
    const detail = await api.getSalesOrder('SO-202607-0001');

    assert.equal(
      calls[0][0],
      '/api/sales/orders?page=3&size=20&customerId=7&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/sales/orders/SO-202607-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].totalAmount, '999999999999.99');
    assert.equal(detail.lines[0].quantity, '123456789012.123456');
    assert.equal(detail.lines[0].unitPrice, '0.000001');
    assert.equal(detail.lines[0].amount, '123456.78');
    assert.equal(detail.lines[0].deliveredQty, '0.000000');
    assert.equal(detail.lines[0].remainingQty, '123456789012.123456');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('sales order create sends the exact narrow body and unwraps warnings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(
        {
          order: order({
            totalAmount: 12.5,
            lines: [
              {
                lineNo: 1,
                productId: 11,
                quantity: 2.5,
                unitPrice: 5,
                amount: 12.5,
                deliveredQty: 0,
                remainingQty: 2.5,
              },
            ],
          }),
          warnings: ['库存不足仅提示'],
        },
        201,
      );
    };
    const api = await import('../src/api/salesOrderApi.ts');
    const result = await api.createSalesOrder({
      customerId: 7,
      orderDate: '2026-07-26',
      remark: '保留字符串',
      checkWarehouseId: 9,
      status: 'APPROVED',
      totalAmount: 'do-not-send',
      lines: [
        {
          productId: 11,
          quantity: '999999999999.999999',
          unitPrice: '0.000001',
          amount: 'do-not-send',
          deliveredQty: 'do-not-send',
          lineNo: 99,
        },
      ],
      audit: 'do-not-send',
    });

    assert.equal(call[0], '/api/sales/orders');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      customerId: 7,
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
    assert.deepEqual(result.warnings, ['库存不足仅提示']);
    assert.equal(result.order.totalAmount, '12.5');
    assert.equal(result.order.lines[0].quantity, '2.5');
    assert.equal(result.order.lines[0].deliveredQty, '0');
    assert.equal(result.order.lines[0].remainingQty, '2.5');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('approve and cancel use only the real POST action URLs and never DELETE', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(order());
    };
    const api = await import('../src/api/salesOrderApi.ts');
    await api.approveSalesOrder('SO/unsafe');
    await api.cancelSalesOrder('SO/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/sales/orders/SO%2Funsafe/approve', 'POST'],
        ['/api/sales/orders/SO%2Funsafe/cancel', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('sales order validation matches quantity and unit-price domain boundaries', async () => {
  const api = await import('../src/api/salesOrderApi.ts');
  const valid = {
    customerId: 7,
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
  assert.equal(api.validateSalesOrderForm(valid), null);
  assert.match(
    api.validateSalesOrderForm({ ...valid, lines: [] }),
    /至少要有一行/,
  );
  assert.match(
    api.validateSalesOrderForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '0' }],
    }),
    /数量必须大于 0/,
  );
  assert.match(
    api.validateSalesOrderForm({
      ...valid,
      lines: [{ ...valid.lines[0], unitPrice: '-0.01' }],
    }),
    /单价必须为非负数/,
  );
  assert.match(
    api.validateSalesOrderForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], quantity: '1' }],
    }),
    /商品不能重复/,
  );
});

test('sales order client and workbench expose no fabricated mutation action', () => {
  const apiSource = readFileSync(
    new URL('../src/api/salesOrderApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/SalesOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|close|reverse)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|关闭|冲销)\s*</);
  assert.doesNotMatch(`${apiSource}\n${workbenchSource}`, /parseFloat\s*\(/);
  assert.doesNotMatch(
    `${apiSource}\n${workbenchSource}`,
    /Number\s*\([^)]*(?:quantity|unitPrice|amount|deliveredQty|remainingQty)/,
  );
});

test('sales order tab is fail-closed on the exact permission code', () => {
  const appSource = readFileSync(
    new URL('../src/App.tsx', import.meta.url),
    'utf8',
  );
  assert.match(
    appSource,
    /const canUseSalesOrders = permissions\.includes\('sales:order'\)/,
  );
  assert.equal(
    appSource.includes(
      '{canUseSalesOrders && <button type="button" role="tab"',
    ),
    true,
  );
  assert.equal(appSource.includes('>销售订单</button>}'), true);
});

test('sales create and status transitions share a synchronous in-flight guard', () => {
  const source = readFileSync(
    new URL('../src/components/SalesOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const saveSource = source.slice(
    source.indexOf('const save = async'),
    source.indexOf("const transition = async"),
  );
  const transitionSource = source.slice(
    source.indexOf("const transition = async"),
    source.indexOf('const customerName ='),
  );
  for (const mutationSource of [saveSource, transitionSource]) {
    assert.match(mutationSource, /mutationInFlight\.current/);
    assert.match(mutationSource, /mutationInFlight\.current = true/);
    assert.match(mutationSource, /finally \{/);
    assert.match(mutationSource, /mutationInFlight\.current = false/);
  }
});

test('sales workbench isolates stale responses and supports same-filter refresh', () => {
  const source = readFileSync(
    new URL('../src/components/SalesOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const listVersion = useRef\(0\)/);
  assert.match(source, /const detailVersion = useRef\(0\)/);
  assert.ok((source.match(/const version = useRef\(0\)/g) ?? []).length >= 2);
  assert.ok(
    (source.match(/setRefreshKey\(\(value\) => value \+ 1\)/g) ?? []).length >=
      3,
  );
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /type="button"/);
});
