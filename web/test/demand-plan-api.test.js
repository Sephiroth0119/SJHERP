import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const plan = (overrides = {}) => ({
  id: 8,
  docNo: 'DP-202610-0001',
  planDate: '2026-10-20',
  status: 'ENABLED',
  remark: '十月预测',
  lines: [
    {
      productId: 21,
      quantity: '12.5',
      unitId: 4,
      dueDate: '2026-10-28',
    },
  ],
  createdBy: 'boss',
  createdAt: '2026-10-20T05:00:00Z',
  updatedBy: 'boss',
  updatedAt: '2026-10-20T05:00:00Z',
  ...overrides,
});

test('demand plan list and detail use exact query paths and keep quantity strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('DP-202610-0001')
        ? response(plan({ lines: [{ productId: 21, quantity: 12.5, unitId: 4, dueDate: '2026-10-28' }] }))
        : response({
            items: [plan({ lines: [{ productId: 21, quantity: 12.5, unitId: 4, dueDate: '2026-10-28' }] })],
            total: 1,
            page: 2,
            size: 20,
          });
    };
    const api = await import('../src/api/demandPlanApi.ts');
    const page = await api.searchDemandPlans('ENABLED', 2);
    const blank = await api.searchDemandPlans('', 1);
    const detail = await api.getDemandPlan('DP-202610-0001');

    assert.equal(calls[0][0], '/api/production/demand-plans?page=2&size=20&status=ENABLED');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/demand-plans?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/demand-plans/DP-202610-0001');
    assert.equal(page.items[0].lines[0].quantity, '12.5');
    assert.equal(detail.lines[0].quantity, '12.5');
    assert.equal(detail.lines[0].dueDate, '2026-10-28');
    assert.equal(blank.page, 2);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('demand plan create sends only the confirmed body and keeps decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(plan(), 201);
    };
    const api = await import('../src/api/demandPlanApi.ts');
    await api.createDemandPlan({
      planDate: '2026-10-20',
      remark: '十月预测',
      status: 'DISABLED',
      id: 99,
      lines: [
        {
          productId: 21,
          quantity: '999999999999.999999',
          unitId: 4,
          dueDate: '2026-10-28',
          extra: 'do-not-send',
        },
      ],
    });

    assert.equal(call[0], '/api/production/demand-plans');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      planDate: '2026-10-20',
      remark: '十月预测',
      lines: [
        {
          productId: 21,
          quantity: '999999999999.999999',
          unitId: 4,
          dueDate: '2026-10-28',
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('demand plan update uses docNo path and sends null for blank remark and due date', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(plan());
    };
    const api = await import('../src/api/demandPlanApi.ts');
    await api.updateDemandPlan('DP-202610-0001', {
      planDate: '2026-10-21',
      remark: '  ',
      lines: [{ productId: 21, quantity: '1', unitId: 4, dueDate: '' }],
    });

    assert.equal(call[0], '/api/production/demand-plans/DP-202610-0001');
    assert.equal(call[1].method, 'PUT');
    assert.deepEqual(JSON.parse(call[1].body), {
      planDate: '2026-10-21',
      remark: null,
      lines: [
        {
          productId: 21,
          quantity: '1',
          unitId: 4,
          dueDate: null,
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('demand plan validation matches date quantity empty-line and duplicate domain boundaries', async () => {
  const api = await import('../src/api/demandPlanApi.ts');
  const valid = {
    planDate: '2026-10-20',
    remark: '',
    lines: [{ productId: 21, quantity: '1.5', unitId: 4, dueDate: '2026-10-28' }],
  };
  assert.equal(api.validateDemandPlanForm(valid), null);
  assert.equal(api.validateDemandPlanForm({ ...valid, lines: [{ ...valid.lines[0], dueDate: '' }] }), null);
  assert.match(api.validateDemandPlanForm({ ...valid, planDate: '' }), /计划日期/);
  assert.match(api.validateDemandPlanForm({ ...valid, planDate: '2026-13-40' }), /计划日期/);
  assert.match(api.validateDemandPlanForm({ ...valid, lines: [] }), /至少需要一行/);
  assert.match(
    api.validateDemandPlanForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '0' }],
    }),
    /数量必须大于 0/,
  );
  assert.match(
    api.validateDemandPlanForm({
      ...valid,
      lines: [{ ...valid.lines[0], productId: null }],
    }),
    /请选择商品/,
  );
  assert.match(
    api.validateDemandPlanForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], quantity: '2' }],
    }),
    /商品\+单位组合不能重复/,
  );
  assert.equal(
    api.validateDemandPlanForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], unitId: 5, quantity: '2' }],
    }),
    null,
  );
});

test('demand plan client and workbench expose no delete reverse or mock data', () => {
  const apiSource = readFileSync(new URL('../src/api/demandPlanApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/DemandPlanWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:删除|作废|冲销)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockDemand|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*quantity/);
});

test('demand plan tab is mounted under production and write is fail-closed on production:plan', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pageSource = readFileSync(
    new URL('../src/components/DemandPlanWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(workbenchSource, /permissions\.includes\('production:plan'\)/);
  assert.equal(workbenchSource.includes('>需求计划</button>'), true);
  assert.equal(workbenchSource.includes('<DemandPlanWorkbench canWrite={canWritePlan} />'), true);
  assert.doesNotMatch(workbenchSource, /Role\.ADMIN|roles\.includes/);
  assert.match(pageSource, /if \(!canWrite \|\| saving\) return/);
  assert.match(pageSource, /if \(!canWrite \|\| !mutationInFlight\.current\.tryAcquire\(\)\) return/);
  assert.match(pageSource, /\{canWrite && <button type="button" className="memory-button memory-button-primary"/);
});

test('demand plan workbench has in-flight guard race isolation and real endpoints', () => {
  const source = readFileSync(
    new URL('../src/components/DemandPlanWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  for (const token of ['listVersion', 'detailVersion', 'supportVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /searchDemandPlans|getDemandPlan|createDemandPlan|updateDemandPlan/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /production:plan/);
  assert.match(source, /GET \/api\/catalog\/products/);
  assert.doesNotMatch(source, /工单|领料|报工|成本结转/);
});
