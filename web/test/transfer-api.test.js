import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const transfer = (overrides = {}) => ({
  docNo: 'TR-202610-0001',
  fromWarehouseId: 5,
  toWarehouseId: 8,
  remark: '调到二号仓',
  status: 'DRAFT',
  lines: [{ lineNo: 1, productId: 11, quantity: '123456789012.123456' }],
  ...overrides,
});

test('transfer list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('TR-202610-0001')
        ? response(transfer())
        : response({
            items: [transfer({ lines: [{ lineNo: 1, productId: 11, quantity: 12.5 }] })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/transferApi.ts');
    const page = await api.searchTransfers(5, 'APPROVED', 3);
    const detail = await api.getTransfer('TR-202610-0001');

    assert.equal(
      calls[0][0],
      '/api/inventory/transfers?page=3&size=20&warehouseId=5&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/inventory/transfers/TR-202610-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].lines[0].quantity, '12.5');
    assert.equal(detail.lines[0].quantity, '123456789012.123456');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('transfer create sends only the narrow body and keeps quantities as strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(transfer(), 201);
    };
    const api = await import('../src/api/transferApi.ts');
    await api.createTransfer({
      fromWarehouseId: 5,
      toWarehouseId: 8,
      remark: '保留字符串',
      status: 'APPROVED',
      audit: 'do-not-send',
      lines: [{ productId: 11, quantity: '999999999999.999999', extra: true }],
    });

    assert.equal(call[0], '/api/inventory/transfers');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      fromWarehouseId: 5,
      toWarehouseId: 8,
      remark: '保留字符串',
      lines: [{ productId: 11, quantity: '999999999999.999999' }],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('approve post and reverse use only real POST action URLs', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(transfer({ lines: [{ lineNo: 1, productId: 11, quantity: 12.5 }] }));
    };
    const api = await import('../src/api/transferApi.ts');
    const approved = await api.approveTransfer('TR/unsafe');
    const posted = await api.postTransfer('TR/unsafe');
    const reversed = await api.reverseTransfer('TR/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/inventory/transfers/TR%2Funsafe/approve', 'POST'],
        ['/api/inventory/transfers/TR%2Funsafe/post', 'POST'],
        ['/api/inventory/transfers/TR%2Funsafe/reverse', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
    for (const result of [approved, posted, reversed]) {
      assert.equal(result.lines[0].quantity, '12.5');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('transfer validation is string based and rejects same warehouse duplicates and overflow', async () => {
  const api = await import('../src/api/transferApi.ts');
  const valid = {
    fromWarehouseId: 5,
    toWarehouseId: 8,
    remark: '',
    lines: [{ productId: 11, quantity: '999999999999.999999' }],
  };
  assert.equal(api.validateTransferForm(valid), null);
  assert.match(api.validateTransferForm({ ...valid, fromWarehouseId: 0 }), /调出仓库/);
  assert.match(api.validateTransferForm({ ...valid, toWarehouseId: 0 }), /调入仓库/);
  assert.match(api.validateTransferForm({ ...valid, toWarehouseId: 5 }), /必须不同/);
  assert.match(api.validateTransferForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(api.validateTransferForm({
    ...valid,
    lines: [{ ...valid.lines[0], quantity: '0' }],
  }), /调拨数量必须大于 0/);
  assert.match(api.validateTransferForm({
    ...valid,
    lines: [{ ...valid.lines[0], quantity: '1.0000001' }],
  }), /小数最多 6 位/);
  assert.match(api.validateTransferForm({
    ...valid,
    lines: [valid.lines[0], { productId: 11, quantity: '1' }],
  }), /商品不能重复/);
});

test('transfer client and workbench expose no fabricated cancel edit delete or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/transferApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/TransferWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockTransfer|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:quantity)/);
});

test('transfer tab is fail-closed on inventory:transfer and uses real workbench', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('inventory:transfer'\)/);
  assert.equal(appSource.includes('>调拨</button>}'), true);
  assert.equal(appSource.includes('<TransferWorkbench />'), true);
  assert.doesNotMatch(appSource, /Role\.WAREHOUSE|roles\.includes/);
});

test('transfer workbench has in-flight guard post reverse confirmation and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/TransferWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /金额守恒/);
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
