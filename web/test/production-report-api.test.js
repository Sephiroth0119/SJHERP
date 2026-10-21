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
  operationSeqNo: 10,
  operationName: '组装',
  workCenter: 'A1',
  reportedHours: '1.500000',
  reportedQty: '5.000000',
  unitId: 4,
  ...overrides,
});

const report = (overrides = {}) => ({
  docNo: 'PR-202610-0001',
  workOrderDocNo: 'WO-202610-0001',
  warehouseId: 10,
  productId: 21,
  completedQty: '5.000000',
  scrapQty: '0.000000',
  unitId: 4,
  inboundCost: null,
  status: 'DRAFT',
  remark: '第一批完工',
  lines: [line()],
  ...overrides,
});

test('production report list and detail use exact query paths and keep quantity strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('PR-202610-0001')
        ? response(report({ completedQty: 5, inboundCost: 300 }))
        : response({
            items: [report({ completedQty: 5, scrapQty: 0 })],
            total: 1,
            page: 2,
            size: 20,
          });
    };
    const api = await import('../src/api/productionReportApi.ts');
    const page = await api.searchProductionReports('WO-202610-0001', 'DRAFT', 2);
    const blank = await api.searchProductionReports('', '', 1);
    const item = await api.getProductionReport('PR-202610-0001');

    assert.equal(calls[0][0], '/api/production/reports?page=2&size=20&workOrderDocNo=WO-202610-0001&status=DRAFT');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/reports?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/reports/PR-202610-0001');
    assert.equal(page.items[0].completedQty, '5');
    assert.equal(item.completedQty, '5');
    assert.equal(item.inboundCost, '300');
    assert.equal(item.status, 'DRAFT');
    assert.equal(blank.page, 2);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('production report create sends hours and quantities as strings without invented cost', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(report(), 201);
    };
    const api = await import('../src/api/productionReportApi.ts');
    await api.createProductionReport({
      workOrderDocNo: ' WO-202610-0001 ',
      warehouseId: 10,
      productId: 21,
      completedQty: '5.000000',
      scrapQty: '',
      unitId: 4,
      remark: '  ',
      extra: 'do-not-send',
      inboundCost: '999',
      lines: [{
        operationSeqNo: '10',
        operationName: '组装',
        workCenter: 'A1',
        reportedHours: '1.500000',
        reportedQty: '',
        unitId: 4,
        costRate: '99',
      }],
    });

    assert.equal(call[0], '/api/production/reports');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      workOrderDocNo: 'WO-202610-0001',
      warehouseId: 10,
      productId: 21,
      completedQty: '5.000000',
      scrapQty: null,
      unitId: 4,
      remark: null,
      lines: [{
        operationSeqNo: 10,
        operationName: '组装',
        workCenter: 'A1',
        reportedHours: '1.500000',
        reportedQty: null,
        unitId: 4,
      }],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('production report transitions use exact write paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(report({ status: 'APPROVED' }));
    };
    const api = await import('../src/api/productionReportApi.ts');
    await api.approveProductionReport('PR-202610-0001');
    await api.postProductionReport('PR-202610-0001');
    await api.cancelProductionReport('PR-202610-0001');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/production/reports/PR-202610-0001/approve', 'POST'],
        ['/api/production/reports/PR-202610-0001/post', 'POST'],
        ['/api/production/reports/PR-202610-0001/cancel', 'POST'],
      ],
    );
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('production report validation matches quantity hour and work-order boundaries', async () => {
  const api = await import('../src/api/productionReportApi.ts');
  const valid = {
    workOrderDocNo: 'WO-1',
    warehouseId: 10,
    productId: 21,
    completedQty: '5',
    scrapQty: '0',
    unitId: 4,
    remark: '',
    lines: [{
      operationSeqNo: '',
      operationName: '',
      workCenter: '',
      reportedHours: '1.5',
      reportedQty: '',
      unitId: 4,
    }],
  };
  assert.equal(api.validateProductionReportForm(valid), null);
  assert.match(api.validateProductionReportForm({ ...valid, workOrderDocNo: '' }), /已开工的工单/);
  assert.match(api.validateProductionReportForm({ ...valid, warehouseId: null }), /完工入库仓库/);
  assert.match(api.validateProductionReportForm({ ...valid, productId: null }), /生产商品/);
  assert.match(api.validateProductionReportForm({ ...valid, completedQty: '0' }), /完工数量必须大于 0/);
  assert.match(api.validateProductionReportForm({ ...valid, scrapQty: '-1' }), /报废数量/);
  assert.match(api.validateProductionReportForm({ ...valid, lines: [] }), /工时记录/);
  assert.match(
    api.validateProductionReportForm({
      ...valid,
      lines: [{ ...valid.lines[0], reportedHours: '0' }],
    }),
    /报工工时必须大于 0/,
  );
  assert.deepEqual(api.availableProductionReportActions('DRAFT'), ['approve', 'cancel']);
  assert.deepEqual(api.availableProductionReportActions('APPROVED'), ['post']);
  assert.deepEqual(api.availableProductionReportActions('COMPLETED'), []);
  assert.equal(api.formatQuantityDisplay('5.000000'), '5');
  assert.equal(api.formatQuantityDisplay('1.500000'), '1.5');
});

test('production report client and workbench expose no delete mock data or cost settlement', () => {
  const apiSource = readFileSync(new URL('../src/api/productionReportApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionReportWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockReport|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:completedQty|scrapQty|reportedHours|inboundCost)/);
  assert.doesNotMatch(workbenchSource, /成本结转|ModulePlaceholder/);
  assert.match(workbenchSource, /一单合一/);
  assert.match(workbenchSource, /报废数量只记录不入库/);
});

test('production report tab is gated on production:report and does not fetch without permission', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pageSource = readFileSync(
    new URL('../src/components/ProductionReportWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(workbenchSource, /permissions\.includes\('production:report'\)/);
  assert.match(workbenchSource, /\{canUseReport && <button type="button" role="tab"/);
  assert.equal(workbenchSource.includes('>报工</button>'), true);
  assert.equal(
    /<ProductionReportWorkbench canUse=\{canUseReport\} canUseWo=\{canUseWo\} focusDocNo=\{focusDocNo\} \/>/.test(
      workbenchSource,
    ),
    true,
  );
  assert.match(pageSource, /if \(!canUse\) \{/);
  assert.match(pageSource, /需要 production:report 权限才能查看和操作报工/);
  assert.match(pageSource, /避免 403 刷屏/);
  assert.match(pageSource, /if \(!canUse \|\| !mutationInFlight\.current\.tryAcquire\(\)\) return/);
  assert.match(pageSource, /window\.confirm/);
  assert.match(pageSource, /availableProductionReportActions/);
  assert.match(pageSource, /role="alert"/);
  assert.match(pageSource, /role="status"/);
  assert.doesNotMatch(workbenchSource, /Role\.ADMIN|roles\.includes/);
});
