import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

test('inventory reports send filters as query and keep quantity/amount strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      const url = String(input);
      if (url.includes('inventory-balance')) {
        return response({
          items: [{
            warehouseId: 1,
            warehouseCode: 'WH1',
            warehouseName: '主仓',
            productId: 9,
            productCode: 'P1',
            productName: '螺丝',
            quantity: 10.5,
            costAmount: '21.00',
            unitCost: 2,
          }],
          total: 1,
          page: 1,
          size: 20,
          totalCostAmount: 21,
        });
      }
      if (url.includes('stock-movement-summary')) {
        return response({
          items: [],
          total: 0,
          page: 1,
          size: 20,
          totalOpeningAmount: '1.00',
          totalInAmount: 2,
          totalOutAmount: '0.50',
          totalEndingAmount: '2.50',
        });
      }
      if (url.includes('purchase-detail')) {
        return response({
          items: [{
            receiptNo: 'PR-1',
            receiptDate: '2026-10-01',
            purchaseOrderNo: 'PO-1',
            supplierId: 3,
            supplierCode: 'S1',
            supplierName: '供应商',
            warehouseId: 1,
            warehouseCode: 'WH1',
            warehouseName: '主仓',
            lineNo: 1,
            productId: 9,
            productCode: 'P1',
            productName: '螺丝',
            quantity: '4',
            unitCost: 1.5,
            amount: '6.00',
            status: 'COMPLETED',
          }],
          total: 1,
          page: 2,
          size: 20,
          totalAmount: 6,
        });
      }
      return response({
        items: [{
          deliveryNo: 'SD-1',
          deliveryDate: '2026-10-02',
          salesOrderNo: 'SO-1',
          customerId: 8,
          customerCode: 'C1',
          customerName: '客户',
          warehouseId: 1,
          warehouseCode: 'WH1',
          warehouseName: '主仓',
          lineNo: 1,
          productId: 9,
          productCode: 'P1',
          productName: '螺丝',
          quantity: '2',
          unitPrice: 10,
          salesAmount: 20,
          cogsAmount: '8.00',
          grossProfit: '12.00',
          status: 'COMPLETED',
        }],
        total: 1,
        page: 1,
        size: 20,
        totalSalesAmount: 20,
        totalCogsAmount: '8.00',
        totalGrossProfit: 12,
      });
    };
    const api = await import('../src/api/inventoryReportApi.ts');
    const inventory = await api.searchInventoryBalance(1, 9, '螺丝', false, 1);
    const movement = await api.searchStockMovement('2026-10-01', '2026-10-31', null, null, '', 1);
    const purchase = await api.searchPurchaseDetail('2026-10-01', '2026-10-31', 3, 9, 1, 'COMPLETED', 2);
    const sales = await api.searchSalesDetail('', '', 8, null, null, '', 1);

    assert.equal(
      calls[0][0],
      '/api/reports/inventory-balance?page=1&size=20&warehouseId=1&productId=9&keyword=%E8%9E%BA%E4%B8%9D&includeZero=false',
    );
    assert.equal(
      calls[1][0],
      '/api/reports/stock-movement-summary?page=1&size=20&fromDate=2026-10-01&toDate=2026-10-31',
    );
    assert.match(calls[2][0], /\/api\/reports\/purchase-detail\?page=2&size=20/);
    assert.match(calls[3][0], /\/api\/reports\/sales-detail\?page=1&size=20&customerId=8/);
    assert.equal(inventory.items[0].quantity, '10.5');
    assert.equal(inventory.totalCostAmount, '21');
    assert.equal(movement.totalInAmount, '2');
    assert.equal(purchase.items[0].unitCost, '1.5');
    assert.equal(sales.items[0].salesAmount, '20');
    assert.equal(sales.totalGrossProfit, '12');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('stock movement rejects inverted dates before calling the API', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let called = 0;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async () => {
      called += 1;
      return response({});
    };
    const api = await import('../src/api/inventoryReportApi.ts');
    await assert.rejects(
      () => api.searchStockMovement('2026-10-31', '2026-10-01', null, null, '', 1),
      /起始日期不能晚于结束日期/,
    );
    assert.equal(called, 0);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('collectAllPages walks remaining pages with export size', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async () => response({});
    const api = await import('../src/api/inventoryReportApi.ts');
    const pages = [
      { items: ['a', 'b'], total: 3, marker: 'first' },
      { items: ['c'], total: 3, marker: 'second' },
    ];
    let page = 0;
    const collected = await api.collectAllPages(async (requested, size) => {
      assert.equal(size, 2);
      page += 1;
      assert.equal(requested, page);
      return pages[requested - 1];
    }, 2);
    assert.deepEqual(collected.items, ['a', 'b', 'c']);
    assert.equal(collected.first.marker, 'first');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('inventory report client is read-only and never uses parseFloat', () => {
  const source = readFileSync(new URL('../src/api/inventoryReportApi.ts', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /method:\s*['"]POST['"]/);
  assert.doesNotMatch(source, /parseFloat\s*\(/);
  assert.match(source, /\/api\/reports\/inventory-balance/);
  assert.match(source, /\/api\/reports\/stock-movement-summary/);
  assert.match(source, /\/api\/reports\/purchase-detail/);
  assert.match(source, /\/api\/reports\/sales-detail/);
});
