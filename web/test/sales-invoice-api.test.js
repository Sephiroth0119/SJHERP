import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300, status, json: async () => body,
});
const invoice = (overrides = {}) => ({
  docNo: 'SINV-202608-0001', salesDeliveryNo: 'SD-202608-0001', customerId: 7,
  invoiceDate: '2026-08-02', dueDate: '2026-09-01', remark: '八月货款',
  status: 'DRAFT', totalAmount: '9999999999999999.99',
  lines: [{ lineNo: 1, deliveryLineNo: 3, productId: 11,
    quantity: '123456789012.123456', unitPrice: '123456789012.123456',
    amount: '1234567890123456.78' }], ...overrides,
});

test('sales invoice list uses exact filters and decimal fields normalize to strings', async () => {
  const previousFetch = globalThis.fetch; const previousStorage = globalThis.localStorage; const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('SINV-202608-0001') ? response(invoice())
        : response({ items: [invoice({ totalAmount: 12.5 })], total: 1, page: 3, size: 20 });
    };
    const api = await import('../src/api/salesInvoiceApi.ts');
    const page = await api.searchSalesInvoices(7, 'SD-202608-0001', 'APPROVED', 3);
    const detail = await api.getSalesInvoice('SINV-202608-0001');
    assert.equal(calls[0][0], '/api/sales/invoices?page=3&size=20&customerId=7&salesDeliveryNo=SD-202608-0001&status=APPROVED');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/sales/invoices/SINV-202608-0001');
    assert.equal(page.items[0].totalAmount, '12.5');
    assert.equal(detail.lines[0].quantity, '123456789012.123456');
    assert.equal(detail.lines[0].unitPrice, '123456789012.123456');
    assert.equal(detail.lines[0].amount, '1234567890123456.78');
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage; }
});

test('sales invoice create sends only the narrow body and preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch; const previousStorage = globalThis.localStorage; let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => { call = [String(input), init]; return response(invoice(), 201); };
    const api = await import('../src/api/salesInvoiceApi.ts');
    await api.createSalesInvoice({
      salesDeliveryNo: 'SD-202608-0001', invoiceDate: '2026-08-02', dueDate: '', remark: '保留字符串',
      status: 'APPROVED', audit: 'do-not-send', lines: [{ deliveryLineNo: 3, productId: 11,
        quantity: '999999999999.999999', unitPrice: '999999999999.999999',
        outstandingInvoiceableQty: '999999999999.999999', cogsAmount: 'do-not-send' }],
    });
    assert.equal(call[0], '/api/sales/invoices'); assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      salesDeliveryNo: 'SD-202608-0001', invoiceDate: '2026-08-02', dueDate: null,
      remark: '保留字符串', lines: [{ deliveryLineNo: 3, productId: 11,
        quantity: '999999999999.999999', unitPrice: '999999999999.999999' }],
    });
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage; }
});

test('invoice-scoped delivery options preserve remaining quantities and use real paths', async () => {
  const previousFetch = globalThis.fetch; const previousStorage = globalThis.localStorage; const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      const item = { docNo: 'SD-202608-0001', salesOrderNo: 'SO-202608-0001', warehouseId: 5,
        remark: null, status: 'COMPLETED',
        lines: [{ deliveryLineNo: 3, productId: 11, quantity: 10,
          invoicedQty: 4, outstandingInvoiceableQty: 6 }] };
      return String(input).endsWith('SD-202608-0001') ? response(item)
        : response({ items: [item], total: 1, page: 2, size: 20 });
    };
    const api = await import('../src/api/salesInvoiceApi.ts');
    const page = await api.searchSalesInvoiceDeliveryOptions(2);
    const detail = await api.getSalesInvoiceDeliveryOption('SD-202608-0001');
    assert.deepEqual(calls.map(([url, init]) => [url, init.method]), [
      ['/api/sales/invoices/delivery-options?page=2&size=20', 'GET'],
      ['/api/sales/invoices/delivery-options/SD-202608-0001', 'GET'],
    ]);
    for (const result of [page.items[0], detail]) {
      assert.equal(result.lines[0].quantity, '10'); assert.equal(result.lines[0].invoicedQty, '4');
      assert.equal(result.lines[0].outstandingInvoiceableQty, '6');
    }
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage; }
});

test('approve post and draft cancel use only real POST actions without reverse or delete', async () => {
  const previousFetch = globalThis.fetch; const previousStorage = globalThis.localStorage; const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => { calls.push([String(input), init]); return response(invoice()); };
    const api = await import('../src/api/salesInvoiceApi.ts');
    await api.approveSalesInvoice('SINV/unsafe'); await api.postSalesInvoice('SINV/unsafe');
    await api.cancelSalesInvoice('SINV/unsafe');
    assert.deepEqual(calls.map(([url, init]) => [url, init.method]), [
      ['/api/sales/invoices/SINV%2Funsafe/approve', 'POST'],
      ['/api/sales/invoices/SINV%2Funsafe/post', 'POST'],
      ['/api/sales/invoices/SINV%2Funsafe/cancel', 'POST'],
    ]);
    assert.equal(calls.some(([url, init]) => url.includes('reverse') || init.method === 'DELETE'), false);
  } finally { globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage; }
});

test('sales invoice validation is string based and rejects excess quantity precision duplicates and overflow', async () => {
  const api = await import('../src/api/salesInvoiceApi.ts');
  const valid = { salesDeliveryNo: 'SD-202608-0001', invoiceDate: '2026-08-02', dueDate: '2026-09-01',
    remark: '', lines: [{ deliveryLineNo: 3, productId: 11, quantity: '999999999999.999999',
      unitPrice: '1.000000', outstandingInvoiceableQty: '999999999999.999999' }] };
  assert.equal(api.validateSalesInvoiceForm(valid), null);
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [{ ...valid.lines[0], quantity: '1000000000000' }] }), /整数最多 12 位/);
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [{ ...valid.lines[0], quantity: '1.0000001' }] }), /小数最多 6 位/);
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [{ ...valid.lines[0], quantity: '999999999999.999999', outstandingInvoiceableQty: '999999999999.999998' }] }), /超过剩余/);
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [valid.lines[0], valid.lines[0]] }), /不能重复/);
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [{ ...valid.lines[0], unitPrice: '-1' }] }), /非负数/);
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [{ ...valid.lines[0], quantity: '999999999999.999999', unitPrice: '999999999999.999999' }] }), /超过系统可保存范围/);
  const largeLine = { deliveryLineNo: 3, productId: 11, quantity: '500000', unitPrice: '12000000000',
    outstandingInvoiceableQty: '500000' };
  assert.match(api.validateSalesInvoiceForm({ ...valid, lines: [largeLine,
    { ...largeLine, deliveryLineNo: 4 }] }), /总金额超过系统可保存范围/);
});

test('in-flight guard blocks duplicate writes and request gate rejects stale responses', async () => {
  const { createInFlightGuard, createRequestGate } = await import('../src/security/workbenchControl.ts');
  const guard = createInFlightGuard();
  assert.equal(guard.tryAcquire(), true);
  assert.equal(guard.tryAcquire(), false);
  assert.equal(guard.isLocked(), true);
  guard.release();
  assert.equal(guard.tryAcquire(), true);
  guard.release();

  const gate = createRequestGate();
  const stale = gate.next();
  const current = gate.next();
  assert.equal(gate.isCurrent(stale), false);
  assert.equal(gate.isCurrent(current), true);
});

test('sales invoice tab is permission-code fail-closed and does not depend on delivery API', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  const workbench = readFileSync(new URL('../src/components/SalesInvoiceWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(app, /permissions\.includes\('sales:invoice'\)/);
  assert.match(app, /<SalesInvoiceWorkbench \/>/);
  assert.doesNotMatch(app, /Role\.ACCOUNTANT|roles\.includes/);
  assert.doesNotMatch(workbench, /salesDeliveryApi/);
});

test('sales invoice workbench has synchronous mutation guard post confirmation race isolation and honest fallbacks', () => {
  const source = readFileSync(new URL('../src/components/SalesInvoiceWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /回写出库行已开票量、生成应收账款与会计凭证/);
  assert.match(source, /任一步失败.*整单回滚|任一步失败.*全部回滚/);
  for (const token of ['listVersion', 'detailVersion', 'deliveryVersion', 'deliveryDetailVersion',
    'customerNameVersion', 'warehouseNameVersion', 'productNameVersion']) assert.match(source, new RegExp(token));
  assert.match(source, /客户 #\$\{id\}（名称不可用）/);
  assert.match(source, /仓库 #\$\{id\}（名称不可用）/);
  assert.match(source, /商品 #\$\{id\}（名称不可用）/);
  assert.match(source, /role="alert"/); assert.match(source, /role="status"/);
});
