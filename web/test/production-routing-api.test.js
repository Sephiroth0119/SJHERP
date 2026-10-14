import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const routing = (overrides = {}) => ({
  id: 9,
  productId: 21,
  version: 1,
  status: 'ENABLED',
  remark: '机加',
  operations: [
    {
      sequenceNo: 10,
      operationName: '车削',
      standardHours: '0.5',
      workCenter: 'WC-01',
      costRate: '12.5',
    },
  ],
  createdBy: 'boss',
  createdAt: '2026-10-14T05:00:00Z',
  updatedBy: 'boss',
  updatedAt: '2026-10-14T05:00:00Z',
  ...overrides,
});

test('routing list and detail use exact query paths and keep hour strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).endsWith('/9')
        ? response(routing({
            operations: [{
              sequenceNo: 10,
              operationName: '车削',
              standardHours: 0.5,
              workCenter: 'WC-01',
              costRate: 12.5,
            }],
          }))
        : response({
            items: [routing({
              operations: [{
                sequenceNo: 10,
                operationName: '车削',
                standardHours: 0.5,
                workCenter: 'WC-01',
                costRate: 12.5,
              }],
            })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/productionRoutingApi.ts');
    const page = await api.searchRoutings(21, 'DISABLED', 3);
    const blank = await api.searchRoutings(null, '', 1);
    const detail = await api.getRouting(9);

    assert.equal(calls[0][0], '/api/production/routings?page=3&size=20&productId=21&status=DISABLED');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/routings?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/routings/9');
    assert.equal(page.items[0].operations[0].standardHours, '0.5');
    assert.equal(page.items[0].operations[0].costRate, '12.5');
    assert.equal(detail.operations[0].standardHours, '0.5');
    assert.equal(blank.total, 1);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('routing create sends only the confirmed body and omits empty reserved fields as null', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(routing(), 201);
    };
    const api = await import('../src/api/productionRoutingApi.ts');
    await api.createRouting({
      productId: 21,
      version: '1',
      remark: '机加',
      status: 'DISABLED',
      operations: [
        {
          sequenceNo: '10',
          operationName: '车削',
          standardHours: '0.500000',
          workCenter: '',
          costRate: '',
          extra: 'do-not-send',
        },
      ],
    });

    assert.equal(call[0], '/api/production/routings');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      productId: 21,
      version: 1,
      remark: '机加',
      operations: [
        {
          sequenceNo: 10,
          operationName: '车削',
          standardHours: '0.500000',
          workCenter: null,
          costRate: null,
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('routing update enable and disable use real write paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(routing({ status: String(input).includes('disable') ? 'DISABLED' : 'ENABLED' }));
    };
    const api = await import('../src/api/productionRoutingApi.ts');
    await api.updateRouting(9, {
      productId: 21,
      version: '1',
      remark: '',
      operations: [
        {
          sequenceNo: '20',
          operationName: '铣削',
          standardHours: '1',
          workCenter: 'WC-02',
          costRate: '8.00',
        },
      ],
    });
    const enabled = await api.enableRouting(9);
    const disabled = await api.disableRouting(9);

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/production/routings/9', 'PUT'],
        ['/api/production/routings/9/enable', 'POST'],
        ['/api/production/routings/9/disable', 'POST'],
      ],
    );
    assert.deepEqual(JSON.parse(calls[0][1].body), {
      productId: 21,
      version: 1,
      remark: null,
      operations: [
        {
          sequenceNo: 20,
          operationName: '铣削',
          standardHours: '1',
          workCenter: 'WC-02',
          costRate: '8.00',
        },
      ],
    });
    assert.equal(enabled.status, 'ENABLED');
    assert.equal(disabled.status, 'DISABLED');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('routing validation matches sequence hours and reserved cost-rate boundaries', async () => {
  const api = await import('../src/api/productionRoutingApi.ts');
  const valid = {
    productId: 21,
    version: '1',
    remark: '',
    operations: [
      {
        sequenceNo: '10',
        operationName: '车削',
        standardHours: '0.5',
        workCenter: '',
        costRate: '',
      },
    ],
  };
  assert.equal(api.validateRoutingForm(valid), null);
  assert.equal(api.validateRoutingForm({
    ...valid,
    operations: [{ ...valid.operations[0], costRate: '0' }],
  }), null);
  assert.match(api.validateRoutingForm({ ...valid, productId: null }), /产品/);
  assert.match(api.validateRoutingForm({ ...valid, operations: [] }), /至少需要一道工序/);
  assert.match(
    api.validateRoutingForm({
      ...valid,
      operations: [
        valid.operations[0],
        { ...valid.operations[0], operationName: '铣削' },
      ],
    }),
    /工序序号重复/,
  );
  assert.match(
    api.validateRoutingForm({
      ...valid,
      operations: [{ ...valid.operations[0], standardHours: '0' }],
    }),
    /标准工时必须大于 0/,
  );
  assert.match(
    api.validateRoutingForm({
      ...valid,
      operations: [{ ...valid.operations[0], costRate: '-1' }],
    }),
    /费率须为非负数/,
  );
});

test('routing client and workbench expose no delete reverse or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/productionRoutingApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/RoutingWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:删除|作废|冲销)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockRouting|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:standardHours|costRate)/);
});

test('routing tab uses real workbench and write is fail-closed on production:routing', () => {
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(workbenchSource, /permissions\.includes\('production:routing'\)/);
  assert.equal(workbenchSource.includes('>工艺路线</button>'), true);
  assert.equal(workbenchSource.includes('<RoutingWorkbench canWrite={canWriteRouting} />'), true);
  assert.doesNotMatch(workbenchSource, /production:plan|production:wo|ModulePlaceholder/);
});

test('routing workbench has in-flight guard confirmation and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/RoutingWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /启用此版本将停用同产品其他启用中的工艺路线/);
  for (const token of ['listVersion', 'detailVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /production:routing/);
});
