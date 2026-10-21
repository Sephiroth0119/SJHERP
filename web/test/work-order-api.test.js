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
  id: 8,
  docNo: 'WO-202610-0001',
  productId: 21,
  plannedQty: '5.000000',
  unitId: 4,
  sourceType: 'MANUAL',
  status: 'DRAFT',
  createdBy: 'boss',
  ...overrides,
});

const detail = (overrides = {}) => ({
  ...summary(),
  completedQty: '0.000000',
  bomVersion: 2,
  routingVersion: 1,
  warehouseId: 10,
  mrpRunDocNo: null,
  plannedStartDate: '2026-10-21',
  plannedEndDate: '2026-10-31',
  remark: '十月工单',
  ...overrides,
});

test('work order list and detail use exact query paths and keep quantity strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('WO-202610-0001')
        ? response(detail({ plannedQty: 5, completedQty: 0 }))
        : response({
            items: [summary({ plannedQty: 5 })],
            total: 1,
            page: 2,
            size: 20,
          });
    };
    const api = await import('../src/api/workOrderApi.ts');
    const page = await api.searchWorkOrders(21, 'DRAFT', 2);
    const blank = await api.searchWorkOrders(null, '', 1);
    const item = await api.getWorkOrder('WO-202610-0001');

    assert.equal(calls[0][0], '/api/production/work-orders?page=2&size=20&productId=21&status=DRAFT');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/work-orders?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/work-orders/WO-202610-0001');
    assert.equal(page.items[0].plannedQty, '5');
    assert.equal(item.plannedQty, '5');
    assert.equal(item.completedQty, '0');
    assert.equal(item.status, 'DRAFT');
    assert.equal(blank.page, 2);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('work order create sends only the confirmed body and keeps decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(detail(), 201);
    };
    const api = await import('../src/api/workOrderApi.ts');
    await api.createWorkOrder({
      productId: 21,
      plannedQty: '999999999999.999999',
      unitId: 4,
      bomVersion: '2',
      routingVersion: '1',
      warehouseId: 10,
      plannedStartDate: '2026-10-21',
      plannedEndDate: '2026-10-31',
      remark: '十月工单',
      extra: 'do-not-send',
      status: 'COMPLETED',
    });

    assert.equal(call[0], '/api/production/work-orders');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      productId: 21,
      plannedQty: '999999999999.999999',
      unitId: 4,
      bomVersion: 2,
      routingVersion: 1,
      warehouseId: 10,
      plannedStartDate: '2026-10-21',
      plannedEndDate: '2026-10-31',
      remark: '十月工单',
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('work order from-mrp and blank optional fields send a narrow body', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(detail({ sourceType: 'MRP_SUGGESTION', mrpRunDocNo: 'MRP-202610-0001' }), 201);
    };
    const api = await import('../src/api/workOrderApi.ts');
    await api.createWorkOrder({
      productId: 21,
      plannedQty: '1',
      unitId: 4,
      bomVersion: '',
      routingVersion: '  ',
      warehouseId: null,
      plannedStartDate: '',
      plannedEndDate: '',
      remark: '  ',
    });
    await api.createWorkOrderFromMrp({
      mrpRunDocNo: 'MRP-202610-0001',
      productId: 21,
      extra: 'do-not-send',
    });

    assert.equal(calls[0][0], '/api/production/work-orders');
    assert.deepEqual(JSON.parse(calls[0][1].body), {
      productId: 21,
      plannedQty: '1',
      unitId: 4,
      bomVersion: null,
      routingVersion: null,
      warehouseId: null,
      plannedStartDate: null,
      plannedEndDate: null,
      remark: null,
    });
    assert.equal(calls[1][0], '/api/production/work-orders/from-mrp');
    assert.equal(calls[1][1].method, 'POST');
    assert.deepEqual(JSON.parse(calls[1][1].body), {
      mrpRunDocNo: 'MRP-202610-0001',
      productId: 21,
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('work order transitions use exact write paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(detail({ status: 'APPROVED' }));
    };
    const api = await import('../src/api/workOrderApi.ts');
    await api.releaseWorkOrder('WO-202610-0001');
    await api.startWorkOrder('WO-202610-0001');
    await api.completeWorkOrder('WO-202610-0001');
    await api.cancelWorkOrder('WO-202610-0001');
    await api.reverseWorkOrder('WO-202610-0001');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/production/work-orders/WO-202610-0001/release', 'POST'],
        ['/api/production/work-orders/WO-202610-0001/start', 'POST'],
        ['/api/production/work-orders/WO-202610-0001/complete', 'POST'],
        ['/api/production/work-orders/WO-202610-0001/cancel', 'POST'],
        ['/api/production/work-orders/WO-202610-0001/reverse', 'POST'],
      ],
    );
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('work order validation matches quantity date and from-mrp boundaries', async () => {
  const api = await import('../src/api/workOrderApi.ts');
  const valid = {
    productId: 21,
    plannedQty: '1.5',
    unitId: 4,
    bomVersion: '',
    routingVersion: '',
    warehouseId: null,
    plannedStartDate: '2026-10-21',
    plannedEndDate: '2026-10-31',
    remark: '',
  };
  assert.equal(api.validateWorkOrderForm(valid), null);
  assert.equal(api.validateWorkOrderForm({ ...valid, plannedStartDate: '', plannedEndDate: '' }), null);
  assert.match(api.validateWorkOrderForm({ ...valid, productId: null }), /生产商品/);
  assert.match(api.validateWorkOrderForm({ ...valid, plannedQty: '0' }), /计划数量必须大于 0/);
  assert.match(api.validateWorkOrderForm({ ...valid, unitId: null }), /计量单位/);
  assert.match(api.validateWorkOrderForm({ ...valid, bomVersion: '0' }), /BOM 版本/);
  assert.match(api.validateWorkOrderForm({ ...valid, plannedStartDate: '2026-13-40' }), /计划开始日期/);
  assert.match(
    api.validateWorkOrderForm({ ...valid, plannedStartDate: '2026-10-31', plannedEndDate: '2026-10-21' }),
    /不能早于开始日期/,
  );
  assert.equal(api.validateFromMrpForm({ mrpRunDocNo: 'MRP-1', productId: 21 }), null);
  assert.match(api.validateFromMrpForm({ mrpRunDocNo: '', productId: 21 }), /MRP 运行/);
  assert.match(api.validateFromMrpForm({ mrpRunDocNo: 'MRP-1', productId: null }), /生产建议/);
  assert.deepEqual(api.availableWorkOrderActions('DRAFT'), ['release', 'cancel']);
  assert.deepEqual(api.availableWorkOrderActions('APPROVED'), ['start', 'reverse']);
  assert.deepEqual(api.availableWorkOrderActions('EXECUTING'), ['complete']);
  assert.deepEqual(api.availableWorkOrderActions('COMPLETED'), []);
  assert.deepEqual(api.availableWorkOrderActions('CANCELLED'), []);
  assert.deepEqual(api.availableWorkOrderActions('REVERSED'), []);
  assert.equal(api.formatQuantityDisplay('5.000000'), '5');
  assert.equal(api.formatQuantityDisplay('5.100000'), '5.1');
  assert.equal(api.formatQuantityDisplay('0.000000'), '0');
  assert.equal(api.formatQuantityDisplay('430'), '430');
});

test('work order client and workbench expose no delete mock data or later execution pages', () => {
  const apiSource = readFileSync(new URL('../src/api/workOrderApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/WorkOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockWorkOrder|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:plannedQty|completedQty)/);
  assert.doesNotMatch(workbenchSource, /领料|退料|齐套|报工|成本结转/);
});

test('work order tab is gated on production:wo and does not fetch without permission', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pageSource = readFileSync(
    new URL('../src/components/WorkOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(workbenchSource, /permissions\.includes\('production:wo'\)/);
  assert.match(workbenchSource, /\{canUseWo && <button type="button" role="tab"/);
  assert.equal(workbenchSource.includes('>工单</button>'), true);
  assert.match(
    workbenchSource,
    /<WorkOrderWorkbench canUse=\{canUseWo\} canUseMrp=\{canUseMrp\} focusDocNo=\{focusDocNo\} \/>/,
  );
  assert.match(pageSource, /if \(!canUse\) \{/);
  assert.match(pageSource, /需要 production:wo 权限才能查看和操作工单/);
  assert.match(pageSource, /避免 403 刷屏/);
  assert.match(pageSource, /if \(!canUse \|\| !mutationInFlight\.current\.tryAcquire\(\)\) return/);
  assert.match(pageSource, /\{canUseMrp && <button type="button" className="memory-button"/);
  assert.doesNotMatch(workbenchSource, /Role\.ADMIN|roles\.includes/);
  assert.doesNotMatch(workbenchSource, /ModulePlaceholder/);
});

test('work order workbench has in-flight guard confirmation and status-legal actions', () => {
  const source = readFileSync(
    new URL('../src/components/WorkOrderWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  for (const token of ['listVersion', 'detailVersion', 'supportVersion', 'warehouseNameVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /searchWorkOrders|getWorkOrder|createWorkOrder|createWorkOrderFromMrp/);
  assert.match(source, /availableWorkOrderActions/);
  assert.match(source, /searchMrpRuns|getMrpRun/);
  assert.match(source, /GET \/api\/catalog\/products/);
  assert.match(source, /GET \/api\/warehouse\/warehouses/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /production:wo/);
  assert.match(source, /下达/);
  assert.match(source, /开工/);
  assert.match(source, /完工/);
  assert.match(source, /作废/);
  assert.match(source, /冲销/);
});
