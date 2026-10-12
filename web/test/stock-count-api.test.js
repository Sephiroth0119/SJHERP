import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const stockCount = (overrides = {}) => ({
  docNo: 'SC-202610-0001',
  warehouseId: 5,
  remark: '十月盘点',
  status: 'DRAFT',
  lines: [{
    lineNo: 1,
    productId: 11,
    snapshotQty: '123456789012.123456',
    countedQty: null,
    diffQty: null,
    enteredUnitCost: '8.5',
  }],
  ...overrides,
});

test('stock count list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('SC-202610-0001')
        ? response(stockCount())
        : response({
            items: [stockCount({
              lines: [{
                lineNo: 1,
                productId: 11,
                snapshotQty: 12.5,
                countedQty: 10,
                diffQty: -2.5,
                enteredUnitCost: null,
              }],
            })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/stockCountApi.ts');
    const page = await api.searchStockCounts(5, 'APPROVED', 3);
    const detail = await api.getStockCount('SC-202610-0001');

    assert.equal(
      calls[0][0],
      '/api/inventory/stock-counts?page=3&size=20&warehouseId=5&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/inventory/stock-counts/SC-202610-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].lines[0].snapshotQty, '12.5');
    assert.equal(page.items[0].lines[0].countedQty, '10');
    assert.equal(page.items[0].lines[0].diffQty, '-2.5');
    assert.equal(page.items[0].lines[0].enteredUnitCost, null);
    assert.equal(detail.lines[0].snapshotQty, '123456789012.123456');
    assert.equal(detail.lines[0].countedQty, null);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('stock count create sends only the narrow body and omits blank unit cost', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(stockCount(), 201);
    };
    const api = await import('../src/api/stockCountApi.ts');
    await api.createStockCount({
      warehouseId: 5,
      remark: '保留字符串',
      status: 'APPROVED',
      audit: 'do-not-send',
      lines: [
        { productId: 11, enteredUnitCost: '8.500000', extra: true },
        { productId: 12, enteredUnitCost: '  ' },
      ],
    });

    assert.equal(call[0], '/api/inventory/stock-counts');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      warehouseId: 5,
      remark: '保留字符串',
      lines: [
        { productId: 11, enteredUnitCost: '8.500000' },
        { productId: 12 },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('enter count approve post and reverse use only real POST action URLs', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(stockCount({
        lines: [{
          lineNo: 1,
          productId: 11,
          snapshotQty: 0,
          countedQty: 2,
          diffQty: 2,
          enteredUnitCost: 8.5,
        }],
      }));
    };
    const api = await import('../src/api/stockCountApi.ts');
    const counted = await api.enterStockCountLine('SC/unsafe', 1, '2.000000');
    const approved = await api.approveStockCount('SC/unsafe');
    const posted = await api.postStockCount('SC/unsafe');
    const reversed = await api.reverseStockCount('SC/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/inventory/stock-counts/SC%2Funsafe/lines/count', 'POST'],
        ['/api/inventory/stock-counts/SC%2Funsafe/approve', 'POST'],
        ['/api/inventory/stock-counts/SC%2Funsafe/post', 'POST'],
        ['/api/inventory/stock-counts/SC%2Funsafe/reverse', 'POST'],
      ],
    );
    assert.deepEqual(JSON.parse(calls[0][1].body), { lineNo: 1, countedQty: '2.000000' });
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
    for (const result of [counted, approved, posted, reversed]) {
      assert.equal(result.lines[0].countedQty, '2');
      assert.equal(result.lines[0].enteredUnitCost, '8.5');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('stock count validation is string based and rejects duplicates overflow and negatives', async () => {
  const api = await import('../src/api/stockCountApi.ts');
  const valid = {
    warehouseId: 5,
    remark: '',
    lines: [{ productId: 11, enteredUnitCost: '' }],
  };
  assert.equal(api.validateStockCountForm(valid), null);
  assert.equal(api.validateStockCountForm({
    ...valid,
    lines: [{ productId: 11, enteredUnitCost: '0' }],
  }), null);
  assert.match(api.validateStockCountForm({ ...valid, warehouseId: 0 }), /仓库/);
  assert.match(api.validateStockCountForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(api.validateStockCountForm({
    ...valid,
    lines: [{ productId: 11, enteredUnitCost: '-1' }],
  }), /盘盈单价必须 ≥ 0/);
  assert.match(api.validateStockCountForm({
    ...valid,
    lines: [{ productId: 11, enteredUnitCost: '1.0000001' }],
  }), /小数最多 6 位/);
  assert.match(api.validateStockCountForm({
    ...valid,
    lines: [valid.lines[0], { productId: 11, enteredUnitCost: '' }],
  }), /商品不能重复/);
  assert.equal(api.validateCountedQty('0'), null);
  assert.equal(api.validateCountedQty('12.123456'), null);
  assert.match(api.validateCountedQty('-1'), /实盘数量必须 ≥ 0/);
  assert.match(api.validateCountedQty('1.0000001'), /小数最多 6 位/);
});

test('stock count client and workbench expose no fabricated cancel edit delete or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/stockCountApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/StockCountWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockStockCount|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:snapshotQty|countedQty|diffQty|enteredUnitCost)/);
});

test('stock count tab is fail-closed on inventory:count and uses real workbench', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('inventory:count'\)/);
  assert.equal(appSource.includes('>盘点</button>}'), true);
  assert.equal(appSource.includes('<StockCountWorkbench />'), true);
  assert.doesNotMatch(appSource, /Role\.WAREHOUSE|roles\.includes/);
});

test('stock count workbench has in-flight guard post reverse confirmation and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/StockCountWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /盘盈\/盘亏/);
  assert.match(source, /整单回滚/);
  assert.match(source, /反向库存/);
  for (const token of [
    'listVersion',
    'detailVersion',
    'warehouseVersion',
    'productVersion',
    'warehouseNameVersion',
    'productNameVersion',
  ]) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /仓库 #\$\{id\}（名称不可用）/);
  assert.match(source, /商品 #\$\{id\}（名称不可用）/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
});
