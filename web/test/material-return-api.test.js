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
  productId: 31,
  quantity: '2.000000',
  unitId: 4,
  srcIssueLineNo: 1,
  returnedCost: null,
  ...overrides,
});

const doc = (overrides = {}) => ({
  docNo: 'MR-202610-0001',
  materialIssueDocNo: 'MI-202610-0001',
  warehouseId: 10,
  status: 'DRAFT',
  remark: '多余退回',
  totalReturnedCost: '0.00',
  lines: [line()],
  ...overrides,
});

test('material return list and detail use exact query paths and keep quantity strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('MR-202610-0001')
        ? response(doc({ totalReturnedCost: 60 }))
        : response({
            items: [doc({ totalReturnedCost: 60.5, lines: [line({ quantity: 2 })] })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/materialReturnApi.ts');
    const page = await api.searchMaterialReturns('MI-202610-0001', 'DRAFT', 3);
    const blank = await api.searchMaterialReturns('', '', 1);
    const item = await api.getMaterialReturn('MR-202610-0001');

    assert.equal(calls[0][0], '/api/production/material-returns?page=3&size=20&materialIssueDocNo=MI-202610-0001&status=DRAFT');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/material-returns?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/material-returns/MR-202610-0001');
    assert.equal(page.items[0].lines[0].quantity, '2');
    assert.equal(page.items[0].totalReturnedCost, '60.5');
    assert.equal(item.totalReturnedCost, '60');
    assert.equal(item.status, 'DRAFT');
    assert.equal(blank.page, 3);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('material return create sends no invented unit cost', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(doc(), 201);
    };
    const api = await import('../src/api/materialReturnApi.ts');
    await api.createMaterialReturn({
      materialIssueDocNo: ' MI-202610-0001 ',
      warehouseId: 10,
      remark: '多余退回',
      extra: 'do-not-send',
      unitCost: '30',
      lines: [{
        productId: 31,
        quantity: '2.000000',
        unitId: 4,
        srcIssueLineNo: 1,
        issuedQty: '4.000000',
        unitCost: '999',
      }],
    });

    assert.equal(call[0], '/api/production/material-returns');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      materialIssueDocNo: 'MI-202610-0001',
      warehouseId: 10,
      remark: '多余退回',
      lines: [{
        productId: 31,
        quantity: '2.000000',
        unitId: 4,
        srcIssueLineNo: 1,
      }],
    });
    assert.equal(JSON.parse(call[1].body).lines[0].unitCost, undefined);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('material return transitions use exact write paths and have no cancel', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(doc({ status: 'APPROVED' }));
    };
    const api = await import('../src/api/materialReturnApi.ts');
    await api.approveMaterialReturn('MR-202610-0001');
    await api.postMaterialReturn('MR-202610-0001');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/production/material-returns/MR-202610-0001/approve', 'POST'],
        ['/api/production/material-returns/MR-202610-0001/post', 'POST'],
      ],
    );
    assert.equal(api.approveMaterialReturn.length, 1);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('material return validation rejects over-return and missing source issue', async () => {
  const api = await import('../src/api/materialReturnApi.ts');
  const valid = {
    materialIssueDocNo: 'MI-1',
    warehouseId: 10,
    remark: '',
    lines: [{ productId: 31, quantity: '2', unitId: 4, srcIssueLineNo: 1, issuedQty: '4' }],
  };
  assert.equal(api.validateMaterialReturnForm(valid), null);
  assert.match(api.validateMaterialReturnForm({ ...valid, materialIssueDocNo: '' }), /原领料单/);
  assert.match(api.validateMaterialReturnForm({ ...valid, warehouseId: null }), /退料仓库/);
  assert.match(api.validateMaterialReturnForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(
    api.validateMaterialReturnForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '5' }],
    }),
    /不可超退/,
  );
  assert.deepEqual(api.availableMaterialReturnActions('DRAFT'), ['approve']);
  assert.deepEqual(api.availableMaterialReturnActions('APPROVED'), ['post']);
  assert.deepEqual(api.availableMaterialReturnActions('COMPLETED'), []);
  const filled = api.linesFromMaterialIssue({
    docNo: 'MI-1',
    workOrderDocNo: 'WO-1',
    warehouseId: 10,
    status: 'COMPLETED',
    remark: null,
    totalIssuedCost: '120.00',
    lines: [{
      lineNo: 1,
      productId: 31,
      requiredQty: '4',
      quantity: '4.000000',
      unitId: 4,
      issuedCost: '120.00',
    }],
  });
  assert.equal(filled[0].issuedQty, '4.000000');
  assert.equal(filled[0].srcIssueLineNo, 1);
});

test('material return client and workbench do not invent unit cost or cancel', () => {
  const apiSource = readFileSync(new URL('../src/api/materialReturnApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/MaterialReturnWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/cancel/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockReturn|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:quantity|returnedCost|issuedCost)/);
  assert.match(workbenchSource, /按原领料成本退回/);
  assert.match(workbenchSource, /不填写单价/);
  assert.doesNotMatch(workbenchSource, /成本结转|ModulePlaceholder/);
});

test('material return tab is gated with material permission and has in-flight guard', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pageSource = readFileSync(
    new URL('../src/components/MaterialReturnWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(
    workbenchSource,
    /<MaterialReturnWorkbench canUse=\{canUseMaterial\} focusDocNo=\{focusDocNo\} \/>/,
  );
  assert.match(pageSource, /需要 production:material 权限才能查看和操作退料/);
  assert.match(pageSource, /避免 403 刷屏/);
  assert.match(pageSource, /if \(!canUse \|\| !mutationInFlight\.current\.tryAcquire\(\)\) return/);
  assert.match(pageSource, /window\.confirm/);
  assert.match(pageSource, /availableMaterialReturnActions/);
  assert.match(pageSource, /role="alert"/);
  assert.match(pageSource, /role="status"/);
  assert.match(pageSource, /不可超退/);
});
