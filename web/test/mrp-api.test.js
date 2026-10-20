import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const summary = (overrides = {}) => ({
  id: 3,
  docNo: 'MRP-202610-0001',
  runAt: '2026-10-20T08:00:00Z',
  warehouseId: 10,
  includeForecast: true,
  includeSalesOrder: true,
  remark: '十月运算',
  createdBy: 'boss',
  ...overrides,
});

const run = (overrides = {}) => ({
  ...summary(),
  suggestions: [
    {
      type: 'PRODUCTION',
      productId: 21,
      level: 0,
      grossRequirement: '100',
      onHand: '10',
      netRequirement: '90',
      baseUnitId: 4,
    },
    {
      type: 'PURCHASE',
      productId: 41,
      level: 2,
      grossRequirement: 480,
      onHand: 50,
      netRequirement: '430.000000',
      baseUnitId: 4,
    },
  ],
  ...overrides,
});

test('MRP history and detail use exact query paths and keep quantity strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('MRP-202610-0001')
        ? response(run())
        : response({ items: [summary()], total: 1, page: 2, size: 20 });
    };
    const api = await import('../src/api/mrpApi.ts');
    const page = await api.searchMrpRuns(2);
    const detail = await api.getMrpRun('MRP-202610-0001');

    assert.equal(calls[0][0], '/api/production/mrp/runs?page=2&size=20');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/mrp/runs/MRP-202610-0001');
    assert.equal(page.items[0].docNo, 'MRP-202610-0001');
    assert.equal(detail.suggestions[0].grossRequirement, '100');
    assert.equal(detail.suggestions[0].onHand, '10');
    assert.equal(detail.suggestions[0].netRequirement, '90');
    assert.equal(detail.suggestions[1].grossRequirement, '480');
    assert.equal(detail.suggestions[1].onHand, '50');
    assert.equal(detail.suggestions[1].netRequirement, '430.000000');
    assert.equal(detail.suggestions[0].type, 'PRODUCTION');
    assert.equal(detail.suggestions[1].type, 'PURCHASE');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('MRP run sends only the confirmed body and keeps boolean source flags', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(run(), 201);
    };
    const api = await import('../src/api/mrpApi.ts');
    await api.runMrp({
      warehouseId: 10,
      includeForecast: false,
      includeSalesOrder: true,
      remark: '十月运算',
      extra: 'do-not-send',
      docNo: 'MRP-FORGED',
    });

    assert.equal(call[0], '/api/production/mrp/runs');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      warehouseId: 10,
      includeForecast: false,
      includeSalesOrder: true,
      remark: '十月运算',
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('MRP validation requires warehouse and at least one demand source', async () => {
  const api = await import('../src/api/mrpApi.ts');
  const valid = {
    warehouseId: 10,
    includeForecast: true,
    includeSalesOrder: false,
    remark: '',
  };
  assert.equal(api.validateMrpRunForm(valid), null);
  assert.match(api.validateMrpRunForm({ ...valid, warehouseId: null }), /请选择仓库/);
  assert.match(api.validateMrpRunForm({ ...valid, warehouseId: 0 }), /请选择仓库/);
  assert.match(
    api.validateMrpRunForm({ ...valid, includeForecast: false, includeSalesOrder: false }),
    /至少选择一个需求来源/,
  );
});

test('MRP client and workbench expose no delete reverse or mock data', () => {
  const apiSource = readFileSync(new URL('../src/api/mrpApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/MrpWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:删除|作废|冲销)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockMrp|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:grossRequirement|onHand|netRequirement)/);
});

test('MRP tab is gated on production:mrp and does not fetch without permission', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pageSource = readFileSync(
    new URL('../src/components/MrpWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(workbenchSource, /permissions\.includes\('production:mrp'\)/);
  assert.match(workbenchSource, /\{canUseMrp && <button type="button" role="tab"/);
  assert.equal(workbenchSource.includes('>MRP</button>'), true);
  assert.equal(workbenchSource.includes('<MrpWorkbench canUse={canUseMrp} />'), true);
  assert.match(pageSource, /if \(!canUse\) \{/);
  assert.match(pageSource, /需要 production:mrp 权限才能查看 MRP 运行历史与触发运算/);
  assert.match(pageSource, /避免 403 刷屏/);
  assert.match(pageSource, /if \(!canUse \|\| !mutationInFlight\.current\.tryAcquire\(\)\) return/);
  assert.doesNotMatch(workbenchSource, /Role\.ADMIN|roles\.includes/);
});

test('MRP workbench has in-flight guard warehouse picker and production/purchase split', () => {
  const source = readFileSync(
    new URL('../src/components/MrpWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  for (const token of ['listVersion', 'detailVersion', 'supportVersion', 'warehouseNameVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /searchMrpRuns|getMrpRun|runMrp/);
  assert.match(source, /searchWarehouses/);
  assert.match(source, /GET \/api\/warehouse\/warehouses/);
  assert.match(source, /生产建议/);
  assert.match(source, /采购建议/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /production:mrp/);
  assert.doesNotMatch(source, /工单|领料|报工|成本结转/);
});
