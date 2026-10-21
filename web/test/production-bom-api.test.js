import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const bom = (overrides = {}) => ({
  id: 8,
  productId: 21,
  version: 2,
  status: 'ENABLED',
  remark: '装配',
  lines: [
    {
      childProductId: 31,
      quantity: '2.5',
      scrapRate: '0.05',
      unitId: 4,
    },
  ],
  createdBy: 'boss',
  createdAt: '2026-10-14T05:00:00Z',
  updatedBy: 'boss',
  updatedAt: '2026-10-14T05:00:00Z',
  ...overrides,
});

const explosion = () => ({
  rootProductId: 21,
  rootQuantity: 10,
  nodes: [
    {
      productId: 31,
      quantity: 26.25,
      unitId: 4,
      level: 1,
      children: [
        {
          productId: 41,
          quantity: '5.000000',
          unitId: 4,
          level: 2,
          children: [],
        },
      ],
    },
  ],
});

test('BOM list and detail use exact query paths and keep quantity strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).endsWith('/8')
        ? response(bom({ lines: [{ childProductId: 31, quantity: 2.5, scrapRate: 0.05, unitId: 4 }] }))
        : response({
            items: [bom({ lines: [{ childProductId: 31, quantity: 2.5, scrapRate: 0.05, unitId: 4 }] })],
            total: 1,
            page: 2,
            size: 20,
          });
    };
    const api = await import('../src/api/productionBomApi.ts');
    const page = await api.searchBoms(21, 'ENABLED', 2);
    const blank = await api.searchBoms(null, '', 1);
    const detail = await api.getBom(8);

    assert.equal(calls[0][0], '/api/production/boms?page=2&size=20&productId=21&status=ENABLED');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/production/boms?page=1&size=20');
    assert.equal(calls[2][0], '/api/production/boms/8');
    assert.equal(page.items[0].lines[0].quantity, '2.5');
    assert.equal(page.items[0].lines[0].scrapRate, '0.05');
    assert.equal(detail.lines[0].quantity, '2.5');
    assert.equal(blank.page, 2);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('BOM create sends only the confirmed body and keeps decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(bom(), 201);
    };
    const api = await import('../src/api/productionBomApi.ts');
    await api.createBom({
      productId: 21,
      version: '2',
      remark: '装配',
      status: 'DISABLED',
      id: 99,
      lines: [
        {
          childProductId: 31,
          quantity: '999999999999.999999',
          scrapRate: '0.050000',
          unitId: 4,
          extra: 'do-not-send',
        },
      ],
    });

    assert.equal(call[0], '/api/production/boms');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      productId: 21,
      version: 2,
      remark: '装配',
      lines: [
        {
          childProductId: 31,
          quantity: '999999999999.999999',
          scrapRate: '0.050000',
          unitId: 4,
        },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('BOM update enable disable and explode use real write and read paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      if (String(input).includes('/explode')) return response(explosion());
      return response(bom({ status: String(input).includes('disable') ? 'DISABLED' : 'ENABLED' }));
    };
    const api = await import('../src/api/productionBomApi.ts');
    await api.updateBom(8, {
      productId: 21,
      version: '2',
      remark: '',
      lines: [{ childProductId: 31, quantity: '1', scrapRate: '', unitId: 4 }],
    });
    const enabled = await api.enableBom(8);
    const disabled = await api.disableBom(8);
    const tree = await api.explodeBom(21, '10.5');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/production/boms/8', 'PUT'],
        ['/api/production/boms/8/enable', 'POST'],
        ['/api/production/boms/8/disable', 'POST'],
        ['/api/production/boms/21/explode?quantity=10.5', 'GET'],
      ],
    );
    assert.deepEqual(JSON.parse(calls[0][1].body), {
      productId: 21,
      version: 2,
      remark: null,
      lines: [
        {
          childProductId: 31,
          quantity: '1',
          scrapRate: null,
          unitId: 4,
        },
      ],
    });
    assert.equal(enabled.status, 'ENABLED');
    assert.equal(disabled.status, 'DISABLED');
    assert.equal(tree.rootQuantity, '10');
    assert.equal(tree.nodes[0].quantity, '26.25');
    assert.equal(tree.nodes[0].children[0].quantity, '5.000000');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('BOM validation matches parent child quantity and scrap-rate domain boundaries', async () => {
  const api = await import('../src/api/productionBomApi.ts');
  const valid = {
    productId: 21,
    version: '1',
    remark: '',
    lines: [{ childProductId: 31, quantity: '1.5', scrapRate: '0.05', unitId: 4 }],
  };
  assert.equal(api.validateBomForm(valid), null);
  assert.equal(api.validateBomForm({ ...valid, lines: [{ ...valid.lines[0], scrapRate: '' }] }), null);
  assert.equal(api.validateBomForm({ ...valid, lines: [{ ...valid.lines[0], scrapRate: '0' }] }), null);
  assert.match(api.validateBomForm({ ...valid, productId: null }), /父件/);
  assert.match(api.validateBomForm({ ...valid, version: '0' }), /版本号/);
  assert.match(api.validateBomForm({ ...valid, lines: [] }), /至少需要一行/);
  assert.match(
    api.validateBomForm({
      ...valid,
      lines: [{ ...valid.lines[0], childProductId: 21 }],
    }),
    /父件自身/,
  );
  assert.match(
    api.validateBomForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], quantity: '2' }],
    }),
    /子件不能重复/,
  );
  assert.match(
    api.validateBomForm({
      ...valid,
      lines: [{ ...valid.lines[0], quantity: '0' }],
    }),
    /用量必须大于 0/,
  );
  assert.match(
    api.validateBomForm({
      ...valid,
      lines: [{ ...valid.lines[0], scrapRate: '1' }],
    }),
    /损耗率/,
  );
  assert.match(api.validateExplodeQuantity('0'), /展开数量必须大于 0/);
  assert.equal(api.validateExplodeQuantity('10.5'), null);
});

test('BOM client and workbench expose no delete reverse or mock data', () => {
  const apiSource = readFileSync(new URL('../src/api/productionBomApi.ts', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/BomWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:删除|作废|冲销)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockBom|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:quantity|scrapRate)/);
});

test('BOM tab is mounted under production and write is fail-closed on production:bom', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  const workbenchSource = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(appSource, /guardedModule === 'production'/);
  assert.equal(appSource.includes('<ProductionWorkbench permissions={permissions} />'), true);
  assert.doesNotMatch(appSource, /ModulePlaceholder/);
  assert.match(workbenchSource, /permissions\.includes\('production:bom'\)/);
  assert.match(workbenchSource, /permissions\.includes\('production:routing'\)/);
  assert.equal(workbenchSource.includes('>BOM</button>'), true);
  assert.equal(workbenchSource.includes('<BomWorkbench canWrite={canWriteBom} />'), true);
  assert.equal(workbenchSource.includes('>需求计划</button>'), true);
  assert.doesNotMatch(workbenchSource, /Role\.ADMIN|roles\.includes/);
  assert.doesNotMatch(workbenchSource, /领料|报工|成本结转/);
});

test('BOM workbench has in-flight guard confirmation explode and race isolation', () => {
  const source = readFileSync(new URL('../src/components/BomWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /启用此版本将停用同产品其他启用中的 BOM/);
  for (const token of ['listVersion', 'detailVersion', 'supportVersion', 'explodeVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /explodeBom/);
  assert.match(source, /searchBoms|getBom|createBom|updateBom/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /production:bom/);
});
