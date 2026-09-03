import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const invoice = (overrides = {}) => ({
  docNo: 'PINV-202607-0001',
  purchaseReceiptNo: 'PR-202607-0001',
  supplierId: 7,
  invoiceDate: '2026-07-26',
  supplierInvoiceNo: 'SUP-INV-001',
  remark: '七月货款',
  status: 'DRAFT',
  totalAmount: '9999999999999999.99',
  lines: [
    {
      lineNo: 1,
      receiptLineNo: 3,
      productId: 11,
      quantity: '123456789012.123456',
      amount: '1234567890123456.78',
    },
  ],
  ...overrides,
});

test('purchase invoice list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('PINV-202607-0001')
        ? response(invoice())
        : response({
            items: [invoice({ totalAmount: 9999999999999999.99 })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/purchaseInvoiceApi.ts');
    const page = await api.searchPurchaseInvoices(
      7,
      'PR-202607-0001',
      'APPROVED',
      3,
    );
    const detail = await api.getPurchaseInvoice('PINV-202607-0001');

    assert.equal(
      calls[0][0],
      '/api/purchase/invoices?page=3&size=20&supplierId=7&purchaseReceiptNo=PR-202607-0001&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/purchase/invoices/PINV-202607-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].totalAmount, '10000000000000000');
    assert.equal(detail.lines[0].quantity, '123456789012.123456');
    assert.equal(detail.lines[0].amount, '1234567890123456.78');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('purchase invoice create sends the exact narrow body with real receipt line references', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(invoice(), 201);
    };
    const api = await import('../src/api/purchaseInvoiceApi.ts');
    await api.createPurchaseInvoice({
      purchaseReceiptNo: 'PR-202607-0001',
      invoiceDate: '2026-07-26',
      supplierInvoiceNo: 'SUP-INV-001',
      remark: '保留字符串',
      status: 'APPROVED',
      lines: [
        {
          receiptLineNo: 3,
          productId: 11,
          quantity: '999999999999.999999',
          amount: '9999999999999999.99',
          outstandingInvoiceableQty: '999999999999.999999',
          unitCost: 'do-not-send',
          lineNo: 99,
        },
      ],
      audit: 'do-not-send',
    });

    assert.equal(call[0], '/api/purchase/invoices');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      purchaseReceiptNo: 'PR-202607-0001',
      invoiceDate: '2026-07-26',
      supplierInvoiceNo: 'SUP-INV-001',
      remark: '保留字符串',
      lines: [
        {
          receiptLineNo: 3,
          quantity: '999999999999.999999',
          amount: '9999999999999999.99',
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('invoice-scoped receipt options use the narrow projection and preserve outstanding quantities', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      const item = {
        docNo: 'PR-202607-0001',
        purchaseOrderNo: 'PO-202607-0001',
        warehouseId: 5,
        receiptDate: '2026-07-26',
        remark: null,
        status: 'COMPLETED',
        totalAmount: 12.5,
        lines: [
          {
            receiptLineNo: 3,
            productId: 11,
            quantity: 10,
            unitCost: 1.25,
            amount: 12.5,
            invoicedQty: 4,
            outstandingInvoiceableQty: 6,
          },
        ],
      };
      return String(input).endsWith('PR-202607-0001')
        ? response(item)
        : response({ items: [item], total: 1, page: 2, size: 20 });
    };
    const api = await import('../src/api/purchaseInvoiceApi.ts');
    const page = await api.searchPurchaseInvoiceReceiptOptions(2);
    const detail = await api.getPurchaseInvoiceReceiptOption('PR-202607-0001');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/purchase/invoices/receipt-options?page=2&size=20', 'GET'],
        [
          '/api/purchase/invoices/receipt-options/PR-202607-0001',
          'GET',
        ],
      ],
    );
    for (const result of [page.items[0], detail]) {
      assert.equal(result.status, 'COMPLETED');
      assert.equal(result.totalAmount, '12.5');
      assert.equal(result.lines[0].quantity, '10');
      assert.equal(result.lines[0].unitCost, '1.25');
      assert.equal(result.lines[0].amount, '12.5');
      assert.equal(result.lines[0].invoicedQty, '4');
      assert.equal(result.lines[0].outstandingInvoiceableQty, '6');
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
        invoice({
          totalAmount: 12.5,
          lines: [
            {
              lineNo: 1,
              receiptLineNo: 3,
              productId: 11,
              quantity: 2.5,
              amount: 12.5,
            },
          ],
        }),
      );
    };
    const api = await import('../src/api/purchaseInvoiceApi.ts');
    const approved = await api.approvePurchaseInvoice('PINV/unsafe');
    const posted = await api.postPurchaseInvoice('PINV/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/purchase/invoices/PINV%2Funsafe/approve', 'POST'],
        ['/api/purchase/invoices/PINV%2Funsafe/post', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
    for (const result of [approved, posted]) {
      assert.equal(result.totalAmount, '12.5');
      assert.equal(result.lines[0].quantity, '2.5');
      assert.equal(result.lines[0].amount, '12.5');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('purchase invoice validation rejects quantities above remaining invoiceable quantity without floats', async () => {
  const api = await import('../src/api/purchaseInvoiceApi.ts');
  const valid = {
    purchaseReceiptNo: 'PR-202607-0001',
    invoiceDate: '2026-07-26',
    supplierInvoiceNo: '',
    remark: '',
    lines: [
      {
        receiptLineNo: 3,
        productId: 11,
        quantity: '999999999999.999999',
        amount: '9999999999999999.99',
        outstandingInvoiceableQty: '999999999999.999999',
      },
    ],
  };
  assert.equal(api.validatePurchaseInvoiceForm(valid), null);
  assert.match(
    api.validatePurchaseInvoiceForm({ ...valid, lines: [] }),
    /至少要有一行/,
  );
  assert.match(
    api.validatePurchaseInvoiceForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '0' }],
    }),
    /数量必须大于 0/,
  );
  assert.match(
    api.validatePurchaseInvoiceForm({
      ...valid,
      lines: [{ ...valid.lines[0], amount: '1.001' }],
    }),
    /金额.*小数最多 2 位/,
  );
  assert.match(
    api.validatePurchaseInvoiceForm({
      ...valid,
      lines: [
        {
          ...valid.lines[0],
          quantity: '999999999999.999999',
          outstandingInvoiceableQty: '999999999999.999998',
        },
      ],
    }),
    /超过剩余可开票量/,
  );
  assert.match(
    api.validatePurchaseInvoiceForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], quantity: '1' }],
    }),
    /入库行不能重复/,
  );
});

test('purchase invoice client and workbench expose no fabricated cancel, edit, delete, or reverse action', () => {
  const apiSource = readFileSync(
    new URL('../src/api/purchaseInvoiceApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/PurchaseInvoiceWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel|reverse)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废|冲销)\s*</);
  assert.doesNotMatch(`${apiSource}\n${workbenchSource}`, /parseFloat\s*\(/);
  assert.doesNotMatch(
    `${apiSource}\n${workbenchSource}`,
    /Number\s*\([^)]*(?:quantity|amount|invoicedQty|outstandingInvoiceableQty)/,
  );
});

test('purchase invoice tab is fail-closed on the exact permission code', () => {
  const appSource = readFileSync(
    new URL('../src/App.tsx', import.meta.url),
    'utf8',
  );
  assert.match(
    appSource,
    /const canUseInvoices = permissions\.includes\('purchase:invoice'\)/,
  );
  assert.equal(
    appSource.includes(
      '{canUseInvoices && <button type="button" role="tab"',
    ),
    true,
  );
  assert.equal(appSource.includes('>采购发票</button>}'), true);
  assert.equal(appSource.includes('<PurchaseInvoiceWorkbench />'), true);

  const workbenchSource = readFileSync(
    new URL('../src/components/PurchaseInvoiceWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(workbenchSource, /canBrowsePurchaseReceipts/);
  assert.doesNotMatch(workbenchSource, /purchaseReceiptApi/);
});

test('create, approve, and post share a synchronous in-flight guard and post requires explicit financial confirmation', () => {
  const source = readFileSync(
    new URL('../src/components/PurchaseInvoiceWorkbench.tsx', import.meta.url),
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
  assert.match(transitionSource, /window\.confirm\s*\(/);
  assert.match(transitionSource, /生成应付/);
  assert.match(transitionSource, /会计凭证/);
  assert.match(source, /回写入库行已开票量/);
});

test('purchase invoice workbench isolates stale responses and supports same-filter refresh', () => {
  const source = readFileSync(
    new URL('../src/components/PurchaseInvoiceWorkbench.tsx', import.meta.url),
    'utf8',
  );
  for (const version of [
    'listVersion',
    'detailVersion',
    'receiptVersion',
    'supplierVersion',
  ]) {
    assert.match(source, new RegExp(`${version}\\.current`));
  }
  assert.match(source, /setRefreshKey\(\(value\) => value \+ 1\)/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /type="button"/);
  assert.match(source, /名称不可用/);
});
