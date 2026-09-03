import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const receipt = (overrides = {}) => ({
  docNo: 'PR-202607-0001',
  purchaseOrderNo: 'PO-202607-0001',
  warehouseId: 5,
  receiptDate: '2026-07-26',
  remark: '第一批到货',
  status: 'DRAFT',
  totalAmount: '999999999999.99',
  lines: [
    {
      lineNo: 1,
      poLineNo: 3,
      productId: 11,
      quantity: '123456789012.123456',
      unitCost: '0.000001',
      amount: '123456.78',
    },
  ],
  ...overrides,
});

test('purchase receipt list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('PR-202607-0001')
        ? response(receipt())
        : response({
            items: [receipt({ totalAmount: 999999999999.99 })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/purchaseReceiptApi.ts');
    const page = await api.searchPurchaseReceipts(
      5,
      'PO-202607-0001',
      'APPROVED',
      3,
    );
    const detail = await api.getPurchaseReceipt('PR-202607-0001');

    assert.equal(
      calls[0][0],
      '/api/purchase/receipts?page=3&size=20&warehouseId=5&purchaseOrderNo=PO-202607-0001&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/purchase/receipts/PR-202607-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].totalAmount, '999999999999.99');
    assert.equal(detail.lines[0].quantity, '123456789012.123456');
    assert.equal(detail.lines[0].unitCost, '0.000001');
    assert.equal(detail.lines[0].amount, '123456.78');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('purchase receipt create sends the exact narrow body with real PO line references', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(receipt(), 201);
    };
    const api = await import('../src/api/purchaseReceiptApi.ts');
    await api.createPurchaseReceipt({
      purchaseOrderNo: 'PO-202607-0001',
      warehouseId: 5,
      receiptDate: '2026-07-26',
      remark: '保留字符串',
      status: 'APPROVED',
      lines: [
        {
          poLineNo: 3,
          productId: 11,
          quantity: '999999999999.999999',
          unitCost: '0.000001',
          outstandingQty: '999999999999.999999',
          amount: 'do-not-send',
          lineNo: 99,
        },
      ],
      audit: 'do-not-send',
    });

    assert.equal(call[0], '/api/purchase/receipts');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      purchaseOrderNo: 'PO-202607-0001',
      warehouseId: 5,
      receiptDate: '2026-07-26',
      remark: '保留字符串',
      lines: [
        {
          poLineNo: 3,
          quantity: '999999999999.999999',
          unitCost: '0.000001',
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('receipt-scoped order options use the narrow read projection and preserve outstanding quantities', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      const item = {
        docNo: 'PO-202607-0001',
        supplierId: 7,
        orderDate: '2026-07-26',
        remark: null,
        status: 'APPROVED',
        totalAmount: 12.5,
        lines: [
          {
            poLineNo: 3,
            productId: 11,
            quantity: 10,
            unitPrice: 1.25,
            receivedQty: 4,
            outstandingQty: 6,
          },
        ],
      };
      return String(input).endsWith('PO-202607-0001')
        ? response(item)
        : response({ items: [item], total: 1, page: 2, size: 20 });
    };
    const api = await import('../src/api/purchaseReceiptApi.ts');
    const page = await api.searchPurchaseReceiptOrderOptions(2);
    const detail = await api.getPurchaseReceiptOrderOption(
      'PO-202607-0001',
    );

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/purchase/receipts/order-options?page=2&size=20', 'GET'],
        [
          '/api/purchase/receipts/order-options/PO-202607-0001',
          'GET',
        ],
      ],
    );
    for (const result of [page.items[0], detail]) {
      assert.equal(result.status, 'APPROVED');
      assert.equal(result.totalAmount, '12.5');
      assert.equal(result.lines[0].quantity, '10');
      assert.equal(result.lines[0].unitPrice, '1.25');
      assert.equal(result.lines[0].receivedQty, '4');
      assert.equal(result.lines[0].outstandingQty, '6');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('approve and post use only the real POST action URLs and normalize number responses', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(
        receipt({
          totalAmount: 12.5,
          lines: [
            {
              lineNo: 1,
              poLineNo: 3,
              productId: 11,
              quantity: 2.5,
              unitCost: 5,
              amount: 12.5,
            },
          ],
        }),
      );
    };
    const api = await import('../src/api/purchaseReceiptApi.ts');
    const approved = await api.approvePurchaseReceipt('PR/unsafe');
    const posted = await api.postPurchaseReceipt('PR/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/purchase/receipts/PR%2Funsafe/approve', 'POST'],
        ['/api/purchase/receipts/PR%2Funsafe/post', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
    for (const result of [approved, posted]) {
      assert.equal(result.totalAmount, '12.5');
      assert.equal(result.lines[0].quantity, '2.5');
      assert.equal(result.lines[0].unitCost, '5');
      assert.equal(result.lines[0].amount, '12.5');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('purchase receipt validation rejects quantities above the PO outstanding quantity without floats', async () => {
  const api = await import('../src/api/purchaseReceiptApi.ts');
  const valid = {
    purchaseOrderNo: 'PO-202607-0001',
    warehouseId: 5,
    receiptDate: '2026-07-26',
    remark: '',
    lines: [
      {
        poLineNo: 3,
        productId: 11,
        quantity: '999999999999.999999',
        unitCost: '0',
        outstandingQty: '999999999999.999999',
      },
    ],
  };
  assert.equal(api.validatePurchaseReceiptForm(valid), null);
  assert.match(
    api.validatePurchaseReceiptForm({ ...valid, lines: [] }),
    /至少要有一行/,
  );
  assert.match(
    api.validatePurchaseReceiptForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '0' }],
    }),
    /数量必须大于 0/,
  );
  assert.match(
    api.validatePurchaseReceiptForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '1000000000000' }],
    }),
    /最多 12 位/,
  );
  assert.match(
    api.validatePurchaseReceiptForm({
      ...valid,
      lines: [
        {
          ...valid.lines[0],
          quantity: '999999999999.999999',
          outstandingQty: '999999999999.999998',
        },
      ],
    }),
    /超过未收量/,
  );
  assert.match(
    api.validatePurchaseReceiptForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], quantity: '1' }],
    }),
    /订单行不能重复/,
  );
});

test('purchase receipt client and workbench expose no fabricated cancel, edit, delete, or reverse action', () => {
  const apiSource = readFileSync(
    new URL('../src/api/purchaseReceiptApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/PurchaseReceiptWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel|reverse)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废|冲销)\s*</);
  assert.doesNotMatch(`${apiSource}\n${workbenchSource}`, /parseFloat\s*\(/);
  assert.doesNotMatch(
    `${apiSource}\n${workbenchSource}`,
    /Number\s*\([^)]*(?:quantity|unitCost|amount|outstandingQty)/,
  );
});

test('purchase receipt tab is fail-closed on the exact permission code', () => {
  const appSource = readFileSync(
    new URL('../src/App.tsx', import.meta.url),
    'utf8',
  );
  assert.match(
    appSource,
    /const canUseReceipts = permissions\.includes\('purchase:receipt'\)/,
  );
  assert.equal(
    appSource.includes(
      '{canUseReceipts && <button type="button" role="tab"',
    ),
    true,
  );
  assert.equal(appSource.includes('>采购入库</button>}'), true);
  assert.equal(appSource.includes('<PurchaseReceiptWorkbench />'), true);

  const workbenchSource = readFileSync(
    new URL('../src/components/PurchaseReceiptWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(workbenchSource, /canBrowsePurchaseOrders/);
  assert.doesNotMatch(workbenchSource, /purchaseOrderApi/);
});

test('create, approve, and post share a synchronous in-flight guard and post requires confirmation', () => {
  const source = readFileSync(
    new URL('../src/components/PurchaseReceiptWorkbench.tsx', import.meta.url),
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
  assert.match(transitionSource, /增加库存/);
  assert.match(transitionSource, /生成会计凭证/);
});

test('purchase receipt workbench isolates stale responses and supports same-filter refresh', () => {
  const source = readFileSync(
    new URL('../src/components/PurchaseReceiptWorkbench.tsx', import.meta.url),
    'utf8',
  );
  for (const version of [
    'listVersion',
    'detailVersion',
    'purchaseOrderVersion',
    'warehouseVersion',
  ]) {
    assert.match(source, new RegExp(`${version}\\.current`));
  }
  assert.match(source, /setRefreshKey\(\(value\) => value \+ 1\)/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /type="button"/);
  assert.match(source, /名称不可用/);
});
