import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const account = (overrides = {}) => ({
  code: '1001',
  name: '库存现金',
  type: 'ASSET',
  typeLabel: '资产',
  balanceDir: 'DEBIT',
  balanceDirLabel: '借',
  parentCode: null,
  level: 1,
  leaf: true,
  enabled: true,
  preset: true,
  ...overrides,
});

test('gl account list and detail use exact query paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('/1001')
        ? response(account())
        : response([account(), account({ code: '222101', name: '应交增值税', parentCode: '2221', level: 2 })]);
    };
    const api = await import('../src/api/glAccountApi.ts');
    const all = await api.listAccounts();
    const filtered = await api.listAccounts('ASSET', true);
    const detail = await api.getAccount('1001');

    assert.equal(calls[0][0], '/api/gl/accounts');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/gl/accounts?leafOnly=true&type=ASSET');
    assert.equal(calls[2][0], '/api/gl/accounts/1001');
    assert.equal(all[0].code, '1001');
    assert.equal(filtered[1].parentCode, '2221');
    assert.equal(detail.preset, true);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('gl account create sends only the confirmed body and omits empty parent as null', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(account({ code: '9001', name: '测试科目', preset: false }), 201);
    };
    const api = await import('../src/api/glAccountApi.ts');
    await api.createAccount({
      code: '9001',
      name: '测试科目',
      type: 'ASSET',
      balanceDir: 'DEBIT',
      parentCode: '',
      isLeaf: true,
      extra: 'do-not-send',
    });

    assert.equal(call[0], '/api/gl/accounts');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      code: '9001',
      name: '测试科目',
      type: 'ASSET',
      balanceDir: 'DEBIT',
      parentCode: null,
      isLeaf: true,
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('gl account enable disable encode the code and never invent PUT', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(account({
        code: '90/01',
        enabled: !String(input).includes('disable'),
        preset: false,
      }));
    };
    const api = await import('../src/api/glAccountApi.ts');
    const enabled = await api.enableAccount('90/01');
    const disabled = await api.disableAccount('90/01');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/gl/accounts/90%2F01/enable', 'POST'],
        ['/api/gl/accounts/90%2F01/disable', 'POST'],
      ],
    );
    assert.equal(enabled.enabled, true);
    assert.equal(disabled.enabled, false);
    assert.equal(typeof api.updateAccount, 'undefined');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('gl account validation requires code name type and balance direction', async () => {
  const api = await import('../src/api/glAccountApi.ts');
  const valid = {
    code: '9001',
    name: '测试科目',
    type: 'ASSET',
    balanceDir: 'DEBIT',
    parentCode: '',
    isLeaf: true,
  };
  assert.equal(api.validateAccountForm(valid), null);
  assert.match(api.validateAccountForm({ ...valid, code: '' }), /编码/);
  assert.match(api.validateAccountForm({ ...valid, name: '' }), /名称/);
  assert.match(
    api.validateAccountForm({ ...valid, type: 'INVALID' }),
    /ASSET \/ LIABILITY \/ EQUITY \/ COST \/ PROFIT_LOSS/,
  );
  assert.match(api.validateAccountForm({ ...valid, balanceDir: 'BOTH' }), /DEBIT \/ CREDIT/);
  assert.match(
    api.validateAccountForm({ ...valid, parentCode: '2221', code: '9001' }),
    /以上级科目编码/,
  );
  assert.equal(api.validateAccountForm({ ...valid, parentCode: '90', code: '9001' }), null);
});

test('gl account forest nests children by parentCode and keeps code order', async () => {
  const api = await import('../src/api/glAccountApi.ts');
  const forest = api.buildAccountForest([
    account({ code: '222101', name: '应交增值税', parentCode: '2221', level: 2 }),
    account({ code: '1001', name: '库存现金', parentCode: null, level: 1 }),
    account({ code: '2221', name: '应交税费', parentCode: null, leaf: false, level: 1 }),
    account({ code: '222102', name: '应交所得税', parentCode: '2221', level: 2 }),
  ]);
  assert.deepEqual(forest.map((node) => node.code), ['1001', '2221']);
  assert.deepEqual(forest[1].children.map((node) => node.code), ['222101', '222102']);
  assert.deepEqual(api.flattenAccountForest(forest).map((node) => node.code), [
    '1001', '2221', '222101', '222102',
  ]);
});

test('gl account client and workbench expose no PUT edit mock or fabricated delete', () => {
  const apiSource = readFileSync(
    new URL('../src/api/glAccountApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/GlAccountWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]PUT['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废|冲销)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|假数据|mockAccount|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\(/);
  assert.match(apiSource, /\/api\/gl\/accounts/);
  assert.match(workbenchSource, /finance:account/);
});

test('account tab is fail-closed on finance:account and uses real workbench', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:account'\)/);
  assert.equal(appSource.includes('>科目</button>'), true);
  assert.match(appSource, /canManageAccounts && <button type="button" role="tab"/);
  assert.equal(appSource.includes('<GlAccountWorkbench canWrite={canManageAccounts} />'), true);
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('gl account workbench has tree list create enable disable and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/GlAccountWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /const listVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /listAccounts/);
  assert.match(source, /createAccount/);
  assert.match(source, /enableAccount/);
  assert.match(source, /disableAccount/);
  assert.match(source, /buildAccountForest/);
  assert.match(source, /树形/);
  assert.match(source, /列表/);
  assert.match(source, /预置科目不可停用/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.doesNotMatch(source, /method:\s*['"]PUT['"]/);
});
