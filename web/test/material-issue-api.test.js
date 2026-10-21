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
  requiredQty: '4.000000',
  quantity: '4.000000',
  unitId: 4,
  issuedCost: null,
  ...overrides,
});

const issue = (overrides = {}) => ({
  docNo: 'MI-202610-0001',
  workOrderDocNo: 'WO-202610-0001',
  warehouseId: 10,
  status: 'DRAFT',
  remark: '第一批领料',
  totalIssuedCost: '0.00',
  lines: [line()],
  ...overrides,
});

test('material issue list and detail use exact query paths and keep quantity strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('MI-202610-0001')
        ? response(issue({ requiredQty: 4, totalIssuedCost: 0 }))
        : response({
            items: [issue({ totalIssuedCost: 120.5, lines: [line({ quantity: 4 })] })],
            total: 1,
            page: 2,
            size: 20,
          });
    };
    const api = await import('../src/api/materialIssueApi.ts');
    const page = await api.searchMaterialIssues('WO-202610-0001', 'DRAFT', 2);
    const blank = await api.searchMaterialIssues('', '', 1);
    const item = await api.getMaterialIssue('MI-202610-0001');

    assert.equal(calls[0][0], '/api/production/material-issues?page=2&size=20&workOrderDocNo=WO-202610-0001&status=DRAFT');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/material-issues?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/material-issues/MI-202610-0001');
    assert.equal(page.items[0].lines[0].quantity, '4');
    assert.equal(page.items[0].totalIssuedCost, '120.5');
    assert.equal(item.lines[0].requiredQty, '4.000000');
    assert.equal(item.status, 'DRAFT');
    assert.equal(blank.page, 2);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('material issue create sends only the confirmed body and keeps decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(issue(), 201);
    };
    const api = await import('../src/api/materialIssueApi.ts');
    await api.createMaterialIssue({
      workOrderDocNo: ' WO-202610-0001 ',
      warehouseId: 10,
      remark: '第一批领料',
      extra: 'do-not-send',
      status: 'COMPLETED',
      lines: [{
        productId: 31,
        requiredQty: '4.500000',
        quantity: '4.500000',
        unitId: 4,
        issuedCost: '999',
      }],
    });

    assert.equal(call[0], '/api/production/material-issues');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      workOrderDocNo: 'WO-202610-0001',
      warehouseId: 10,
      remark: '第一批领料',
      lines: [{
        productId: 31,
        requiredQty: '4.500000',
        quantity: '4.500000',
        unitId: 4,
      }],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('material issue transitions and kitting use exact paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('kitting-check')
        ? response({
            workOrderDocNo: 'WO-202610-0001',
            warehouseId: 10,
            kitted: false,
            lines: [{
              productId: 31,
              unitId: 4,
              required: '4.000000',
              available: '1.000000',
              shortage: '3.000000',
            }],
          })
        : response(issue({ status: 'APPROVED' }));
    };
    const api = await import('../src/api/materialIssueApi.ts');
    await api.approveMaterialIssue('MI-202610-0001');
    await api.postMaterialIssue('MI-202610-0001');
    await api.cancelMaterialIssue('MI-202610-0001');
    const kitting = await api.checkKitting('WO-202610-0001', 10);

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/production/material-issues/MI-202610-0001/approve', 'POST'],
        ['/api/production/material-issues/MI-202610-0001/post', 'POST'],
        ['/api/production/material-issues/MI-202610-0001/cancel', 'POST'],
        ['/api/production/kitting-check?workOrderDocNo=WO-202610-0001&warehouseId=10', 'GET'],
      ],
    );
    assert.equal(kitting.kitted, false);
    assert.equal(kitting.lines[0].required, '4.000000');
    assert.equal(kitting.lines[0].shortage, '3.000000');
    const filled = api.linesFromKitting(kitting);
    assert.equal(filled[0].quantity, '4.000000');
    assert.equal(filled[0].requiredQty, '4.000000');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('material issue validation and legal actions match backend boundaries', async () => {
  const api = await import('../src/api/materialIssueApi.ts');
  const valid = {
    workOrderDocNo: 'WO-1',
    warehouseId: 10,
    remark: '',
    lines: [{ productId: 31, requiredQty: '0', quantity: '1.5', unitId: 4 }],
  };
  assert.equal(api.validateMaterialIssueForm(valid), null);
  assert.match(api.validateMaterialIssueForm({ ...valid, workOrderDocNo: '  ' }), /已开工的工单/);
  assert.match(api.validateMaterialIssueForm({ ...valid, warehouseId: null }), /领料仓库/);
  assert.match(api.validateMaterialIssueForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(
    api.validateMaterialIssueForm({ ...valid, lines: [{ ...valid.lines[0], quantity: '0' }] }),
    /实领数量必须大于 0/,
  );
  assert.match(api.validateKittingQuery('', 10), /工单号/);
  assert.match(api.validateKittingQuery('WO-1', null), /仓库/);
  assert.equal(api.validateKittingQuery('WO-1', 10), null);
  assert.deepEqual(api.availableMaterialIssueActions('DRAFT'), ['approve', 'cancel']);
  assert.deepEqual(api.availableMaterialIssueActions('APPROVED'), ['post']);
  assert.deepEqual(api.availableMaterialIssueActions('COMPLETED'), []);
  assert.equal(api.formatQuantityDisplay('4.000000'), '4');
  assert.equal(api.formatQuantityDisplay('4.100000'), '4.1');
});

test('material issue client and workbench expose no delete mock data or unit prices', () => {
  const apiSource = readFileSync(new URL('../src/api/materialIssueApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/MaterialIssueWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockIssue|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:requiredQty|quantity|issuedCost)/);
  assert.doesNotMatch(workbenchSource, /成本结转|ModulePlaceholder/);
  assert.match(workbenchSource, /仅提示不阻断领料/);
});

test('material issue tab is gated on production:material and does not fetch without permission', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const pageSource = readFileSync(
    new URL('../src/components/MaterialIssueWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(workbenchSource, /permissions\.includes\('production:material'\)/);
  assert.match(workbenchSource, /\{canUseMaterial && <button type="button" role="tab"/);
  assert.equal(workbenchSource.includes('>领料</button>'), true);
  assert.equal(workbenchSource.includes('>退料</button>'), true);
  assert.equal(
    /<MaterialIssueWorkbench canUse=\{canUseMaterial\} canUseWo=\{canUseWo\} focusDocNo=\{focusDocNo\} \/>/.test(
      workbenchSource,
    ),
    true,
  );
  assert.match(pageSource, /if \(!canUse\) \{/);
  assert.match(pageSource, /需要 production:material 权限才能查看和操作领料、齐套/);
  assert.match(pageSource, /避免 403 刷屏/);
  assert.match(pageSource, /if \(!canUse \|\| !mutationInFlight\.current\.tryAcquire\(\)\) return/);
  assert.doesNotMatch(workbenchSource, /Role\.ADMIN|roles\.includes/);
  assert.doesNotMatch(workbenchSource, /ModulePlaceholder/);
});

test('material issue workbench has in-flight guard confirmation and kitting panel', () => {
  const source = readFileSync(
    new URL('../src/components/MaterialIssueWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  for (const token of ['listVersion', 'detailVersion', 'supportVersion', 'warehouseNameVersion', 'kittingVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /searchMaterialIssues|getMaterialIssue|createMaterialIssue|checkKitting/);
  assert.match(source, /availableMaterialIssueActions/);
  assert.match(source, /GET \/api\/production\/kitting-check/);
  assert.match(source, /GET \/api\/catalog\/products/);
  assert.match(source, /GET \/api\/warehouse\/warehouses/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /production:material/);
  assert.match(source, /审核/);
  assert.match(source, /过账/);
  assert.match(source, /作废/);
  assert.match(source, /工单须已开工/);
});
