import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const delivery = (overrides = {}) => ({
  docNo: 'SD-202607-0001',
  salesOrderNo: 'SO-202607-0001',
  warehouseId: 5,
  remark: '第一批发货',
  status: 'DRAFT',
  totalCogs: '999999999999.99',
  lines: [
    {
      lineNo: 1,
      soLineNo: 3,
      productId: 11,
      quantity: '123456789012.123456',
      cogsAmount: null,
    },
  ],
  ...overrides,
});

test('sales delivery list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('SD-202607-0001')
        ? response(delivery())
        : response({
            items: [delivery({ totalCogs: 999999999999.99 })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/salesDeliveryApi.ts');
    const page = await api.searchSalesDeliveries(
      5,
      'SO-202607-0001',
      'APPROVED',
      3,
    );
    const detail = await api.getSalesDelivery('SD-202607-0001');

    assert.equal(
      calls[0][0],
      '/api/sales/deliveries?page=3&size=20&warehouseId=5&salesOrderNo=SO-202607-0001&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/sales/deliveries/SD-202607-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].totalCogs, '999999999999.99');
    assert.equal(detail.lines[0].quantity, '123456789012.123456');
    assert.equal(detail.lines[0].cogsAmount, null);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('sales delivery create sends exact narrow body with real sales order line references', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(delivery(), 201);
    };
    const api = await import('../src/api/salesDeliveryApi.ts');
    await api.createSalesDelivery({
      salesOrderNo: 'SO-202607-0001',
      warehouseId: 5,
      remark: '保留字符串',
      status: 'APPROVED',
      lines: [
        {
          soLineNo: 3,
          productId: 11,
          quantity: '999999999999.999999',
          remainingQty: '999999999999.999999',
          amount: 'do-not-send',
          lineNo: 99,
        },
      ],
      audit: 'do-not-send',
    });

    assert.equal(call[0], '/api/sales/deliveries');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      salesOrderNo: 'SO-202607-0001',
      warehouseId: 5,
      remark: '保留字符串',
      lines: [
        {
          soLineNo: 3,
          productId: 11,
          quantity: '999999999999.999999',
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('delivery-scoped order options preserve APPROVED and EXECUTING remaining quantities', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      const item = {
        docNo: 'SO-202607-0001',
        customerId: 7,
        orderDate: '2026-07-26',
        remark: null,
        status: 'EXECUTING',
        lines: [
          {
            soLineNo: 3,
            productId: 11,
            quantity: 10,
            unitPrice: 1.25,
            amount: 12.5,
            deliveredQty: 4,
            remainingQty: 6,
          },
        ],
      };
      return String(input).endsWith('SO-202607-0001')
        ? response(item)
        : response({ items: [item], total: 1, page: 2, size: 20 });
    };
    const api = await import('../src/api/salesDeliveryApi.ts');
    const page = await api.searchSalesDeliveryOrderOptions(2);
    const detail = await api.getSalesDeliveryOrderOption('SO-202607-0001');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/sales/deliveries/order-options?page=2&size=20', 'GET'],
        ['/api/sales/deliveries/order-options/SO-202607-0001', 'GET'],
      ],
    );
    for (const result of [page.items[0], detail]) {
      assert.equal(result.status, 'EXECUTING');
      assert.equal(result.lines[0].quantity, '10');
      assert.equal(result.lines[0].unitPrice, '1.25');
      assert.equal(result.lines[0].amount, '12.5');
      assert.equal(result.lines[0].deliveredQty, '4');
      assert.equal(result.lines[0].remainingQty, '6');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('approve post and cancel use only real POST action URLs and normalize number responses', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(
        delivery({
          totalCogs: 12.5,
          lines: [
            {
              lineNo: 1,
              soLineNo: 3,
              productId: 11,
              quantity: 2.5,
              cogsAmount: 12.5,
            },
          ],
        }),
      );
    };
    const api = await import('../src/api/salesDeliveryApi.ts');
    const approved = await api.approveSalesDelivery('SD/unsafe');
    const posted = await api.postSalesDelivery('SD/unsafe');
    const cancelled = await api.cancelSalesDelivery('SD/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/sales/deliveries/SD%2Funsafe/approve', 'POST'],
        ['/api/sales/deliveries/SD%2Funsafe/post', 'POST'],
        ['/api/sales/deliveries/SD%2Funsafe/cancel', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
    for (const result of [approved, posted, cancelled]) {
      assert.equal(result.totalCogs, '12.5');
      assert.equal(result.lines[0].quantity, '2.5');
      assert.equal(result.lines[0].cogsAmount, '12.5');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('sales delivery validation rejects quantities above remaining quantity without floats', async () => {
  const api = await import('../src/api/salesDeliveryApi.ts');
  const valid = {
    salesOrderNo: 'SO-202607-0001',
    warehouseId: 5,
    remark: '',
    lines: [
      {
        soLineNo: 3,
        productId: 11,
        quantity: '999999999999.999999',
        remainingQty: '999999999999.999999',
      },
    ],
  };
  assert.equal(api.validateSalesDeliveryForm(valid), null);
  assert.match(
    api.validateSalesDeliveryForm({ ...valid, lines: [] }),
    /至少要有一行/,
  );
  assert.match(
    api.validateSalesDeliveryForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '0' }],
    }),
    /数量必须大于 0/,
  );
  assert.match(
    api.validateSalesDeliveryForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '1000000000000' }],
    }),
    /最多 12 位/,
  );
  assert.match(
    api.validateSalesDeliveryForm({
      ...valid,
      lines: [
        {
          ...valid.lines[0],
          quantity: '999999999999.999999',
          remainingQty: '999999999999.999998',
        },
      ],
    }),
    /超过剩余可发量/,
  );
  assert.match(
    api.validateSalesDeliveryForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], quantity: '1' }],
    }),
    /订单行不能重复/,
  );
});

test('sales delivery client exposes cancel but no fabricated edit delete or reverse action', () => {
  const apiSource = readFileSync(
    new URL('../src/api/salesDeliveryApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/SalesDeliveryWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|reverse)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|冲销)\s*</);
  assert.match(apiSource, /cancelSalesDelivery/);
  assert.match(workbenchSource, />\s*作废\s*</);
  assert.doesNotMatch(`${apiSource}\n${workbenchSource}`, /parseFloat\s*\(/);
  assert.doesNotMatch(
    `${apiSource}\n${workbenchSource}`,
    /Number\s*\([^)]*(?:quantity|cogsAmount|totalCogs|remainingQty)/,
  );
});

test('sales delivery tab is fail-closed on the exact permission code', () => {
  const appSource = readFileSync(
    new URL('../src/App.tsx', import.meta.url),
    'utf8',
  );
  assert.match(
    appSource,
    /const canUseSalesDeliveries = permissions\.includes\('sales:delivery'\)/,
  );
  assert.equal(
    appSource.includes(
      '{canUseSalesDeliveries && <button type="button" role="tab"',
    ),
    true,
  );
  assert.equal(appSource.includes('>销售出库</button>}'), true);
  assert.equal(appSource.includes('<SalesDeliveryWorkbench />'), true);

  const workbenchSource = readFileSync(
    new URL('../src/components/SalesDeliveryWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(workbenchSource, /canBrowseSalesOrders/);
  assert.doesNotMatch(workbenchSource, /salesOrderApi/);
});

test('create approve post and cancel share synchronous guard and post confirms all real effects', () => {
  const source = readFileSync(
    new URL('../src/components/SalesDeliveryWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const saveSource = source.slice(
    source.indexOf('const save = async'),
    source.indexOf("const transition = async"),
  );
  const transitionSource = source.slice(
    source.indexOf("const transition = async"),
    source.indexOf('const warehouseName ='),
  );
  for (const mutationSource of [saveSource, transitionSource]) {
    assert.match(mutationSource, /mutationInFlight\.current/);
    assert.match(mutationSource, /mutationInFlight\.current = true/);
    assert.match(mutationSource, /finally \{/);
    assert.match(mutationSource, /mutationInFlight\.current = false/);
  }
  assert.match(transitionSource, /window\.confirm\s*\(/);
  for (const effect of [
    '扣减库存',
    '移动加权',
    'COGS',
    '回写销售订单发货量',
    '生成自动凭证',
    '库存不足',
    '整张出库单',
    '回滚',
  ]) {
    assert.match(transitionSource, new RegExp(effect));
  }
});

test('sales delivery workbench isolates stale responses and provides honest accessible feedback', () => {
  const source = readFileSync(
    new URL('../src/components/SalesDeliveryWorkbench.tsx', import.meta.url),
    'utf8',
  );
  for (const version of [
    'listVersion',
    'detailVersion',
    'salesOrderVersion',
    'warehouseVersion',
    'warehouseNameVersion',
    'productVersion',
  ]) {
    assert.match(source, new RegExp(`${version}\\.current`));
  }
  assert.match(source, /setRefreshKey\(\(value\) => value \+ 1\)/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /type="button"/);
  assert.match(source, /名称不可用/);
  assert.match(source, /库存不足.*整张出库单.*回滚/s);
});
