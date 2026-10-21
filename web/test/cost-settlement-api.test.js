import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const line = (overrides = {}) => ({
  lineNo: 1,
  workOrderDocNo: 'WO-202610-0001',
  materialCost: '100.000000',
  rawMaterialCost: '80.000000',
  goodsMaterialCost: '20.000000',
  laborCost: '30.000000',
  overheadCost: '10.000000',
  completedQty: '5.000000',
  completedCost: '135.000000',
  wipQty: '2.000000',
  wipCompletionPct: '50.00',
  wipCost: '5.000000',
  alreadyTransferred: '0.000000',
  costAdjustIdemKey: null,
  voucherDocNo: null,
  ...overrides,
});

const settlement = (overrides = {}) => ({
  docNo: 'PC-202610-0001',
  period: '202610',
  status: 'DRAFT',
  remark: '十月结转',
  lines: [line()],
  ...overrides,
});

test('cost settlement list and detail use exact query paths and keep amount strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('PC-202610-0001')
        ? response(settlement({
            lines: [line({ completedCost: 135, laborCost: 30 })],
          }))
        : response({
            items: [settlement({
              lines: [line({ materialCost: 100, wipQty: 2 })],
            })],
            total: 1,
            page: 2,
            size: 20,
          });
    };
    const api = await import('../src/api/costSettlementApi.ts');
    const page = await api.searchCostSettlements('202610', 'DRAFT', 2);
    const blank = await api.searchCostSettlements('', '', 1);
    const item = await api.getCostSettlement('PC-202610-0001');

    assert.equal(calls[0][0], '/api/production/cost-settlements?page=2&size=20&period=202610&status=DRAFT');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/cost-settlements?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/cost-settlements/PC-202610-0001');
    assert.equal(page.items[0].lines[0].materialCost, '100');
    assert.equal(page.items[0].lines[0].wipQty, '2');
    assert.equal(item.lines[0].completedCost, '135');
    assert.equal(item.lines[0].laborCost, '30');
    assert.equal(item.status, 'DRAFT');
    assert.equal(blank.page, 2);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('cost settlement create sends period and wip fields as strings without invented costs', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(settlement(), 201);
    };
    const api = await import('../src/api/costSettlementApi.ts');
    await api.createCostSettlement({
      period: ' 202610 ',
      remark: '  ',
      extra: 'do-not-send',
      materialCost: '999',
      lines: [{
        workOrderDocNo: ' WO-202610-0001 ',
        wipQty: '2.000000',
        wipCompletionPct: '50.00',
        laborCost: '99',
        overheadCost: '1',
      }],
    });

    assert.equal(call[0], '/api/production/cost-settlements');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      period: '202610',
      remark: null,
      lines: [{
        workOrderDocNo: 'WO-202610-0001',
        wipQty: '2.000000',
        wipCompletionPct: '50.00',
      }],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('cost settlement transitions use exact write paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(settlement({ status: 'APPROVED' }));
    };
    const api = await import('../src/api/costSettlementApi.ts');
    await api.approveCostSettlement('PC-202610-0001');
    await api.postCostSettlement('PC-202610-0001');
    await api.cancelCostSettlement('PC-202610-0001');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/production/cost-settlements/PC-202610-0001/approve', 'POST'],
        ['/api/production/cost-settlements/PC-202610-0001/post', 'POST'],
        ['/api/production/cost-settlements/PC-202610-0001/cancel', 'POST'],
      ],
    );
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('cost settlement validation matches period wip and work-order boundaries', async () => {
  const api = await import('../src/api/costSettlementApi.ts');
  const valid = {
    period: '202610',
    remark: '',
    lines: [{
      workOrderDocNo: 'WO-1',
      wipQty: '2',
      wipCompletionPct: '50',
    }],
  };
  assert.equal(api.validateCostSettlementForm(valid), null);
  assert.match(api.validateCostSettlementForm({ ...valid, period: '' }), /请填写账期/);
  assert.match(api.validateCostSettlementForm({ ...valid, period: '2026-10' }), /yyyyMM/);
  assert.match(api.validateCostSettlementForm({ ...valid, period: '202613' }), /月份须为 01-12/);
  assert.match(api.validateCostSettlementForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(
    api.validateCostSettlementForm({
      ...valid,
      lines: [{ ...valid.lines[0], workOrderDocNo: '' }],
    }),
    /请填写工单号/,
  );
  assert.match(
    api.validateCostSettlementForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0] }],
    }),
    /不能出现多行/,
  );
  assert.match(
    api.validateCostSettlementForm({
      ...valid,
      lines: [{ ...valid.lines[0], wipQty: '-1' }],
    }),
    /在产数量须为非负数/,
  );
  assert.match(
    api.validateCostSettlementForm({
      ...valid,
      lines: [{ ...valid.lines[0], wipCompletionPct: '101' }],
    }),
    /完工程度必须在 0–100/,
  );
  assert.equal(
    api.validateCostSettlementForm({
      ...valid,
      lines: [{ workOrderDocNo: 'WO-1', wipQty: '', wipCompletionPct: '' }],
    }),
    null,
  );
  assert.deepEqual(api.availableCostSettlementActions('DRAFT'), ['approve', 'cancel']);
  assert.deepEqual(api.availableCostSettlementActions('APPROVED'), ['post']);
  assert.deepEqual(api.availableCostSettlementActions('COMPLETED'), []);
  assert.equal(api.formatQuantityDisplay('5.000000'), '5');
  assert.equal(api.formatQuantityDisplay('50.00'), '50');
});

test('cost settlement client and workbench expose no delete mock data or reverse', () => {
  const apiSource = readFileSync(new URL('../src/api/costSettlementApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/CostSettlementWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/reverse/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockSettlement|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:wipQty|wipCompletionPct|materialCost|laborCost|overheadCost|completedCost)/);
  assert.match(workbenchSource, /料按净领料、工费按约当产量由后端计算/);
  assert.match(workbenchSource, /界面不重算/);
  assert.match(workbenchSource, /CostAdjust/);
  assert.doesNotMatch(workbenchSource, /ModulePlaceholder/);
});

test('cost settlement tab is gated on production:cost and does not fetch without permission', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pageSource = readFileSync(
    new URL('../src/components/CostSettlementWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pickerSource = readFileSync(
    new URL('../src/components/productionReferencePickers.tsx', import.meta.url),
    'utf8',
  );
  assert.match(workbenchSource, /permissions\.includes\('production:cost'\)/);
  assert.match(workbenchSource, /\{canUseCost && <button type="button" role="tab"/);
  assert.equal(workbenchSource.includes('>成本结转</button>'), true);
  assert.equal(
    /<CostSettlementWorkbench canUse=\{canUseCost\} canUseWo=\{canUseWo\} focusDocNo=\{focusDocNo\} \/>/.test(
      workbenchSource,
    ),
    true,
  );
  assert.match(pageSource, /if \(!canUse\) \{/);
  assert.match(pageSource, /需要 production:cost 权限才能查看和操作成本结转/);
  assert.match(pageSource, /避免 403 刷屏/);
  assert.match(pageSource, /if \(!canUse \|\| !mutationInFlight\.current\.tryAcquire\(\)\) return/);
  assert.match(pageSource, /window\.confirm/);
  assert.match(pageSource, /availableCostSettlementActions/);
  assert.match(pageSource, /role="alert"/);
  assert.match(pageSource, /role="status"/);
  assert.match(pageSource, /listPeriods/);
  assert.match(pickerSource, /CostSettlementWorkOrderPicker/);
  assert.match(pickerSource, /无 production:wo 时请在行内手工填写工单号，不查询工单列表/);
  assert.doesNotMatch(workbenchSource, /Role\.ADMIN|roles\.includes/);
});

test('cost settlement workbench has in-flight guard confirmation and request gates', () => {
  const source = readFileSync(
    new URL('../src/components/CostSettlementWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  for (const token of ['listVersion', 'detailVersion', 'periodVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /createRequestGate/);
  assert.match(source, /账期已关则整单回滚/);
});
