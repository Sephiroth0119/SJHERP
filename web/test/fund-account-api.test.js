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
  id: 10,
  code: 'FA-202610-0001',
  name: '工行基本户',
  accountType: 'BANK',
  accountTypeLabel: '银行存款',
  glAccountCode: '1002',
  bankName: '工商银行',
  accountNo: '6222',
  status: 'ENABLED',
  createdBy: 'accountant',
  createdAt: '2026-10-12T05:00:00Z',
  updatedBy: 'accountant',
  updatedAt: '2026-10-12T05:00:00Z',
  ...overrides,
});

test('fund account list and detail use exact query paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).endsWith('/10')
        ? response(account())
        : response({ items: [account()], total: 1, page: 2, size: 20 });
    };
    const api = await import('../src/api/fundAccountApi.ts');
    const page = await api.searchFundAccounts('工行', 'ENABLED', 2);
    const detail = await api.getFundAccount(10);

    assert.equal(
      calls[0][0],
      '/api/fund/accounts?page=2&size=20&keyword=%E5%B7%A5%E8%A1%8C&status=ENABLED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/fund/accounts/10');
    assert.equal(detail.glAccountCode, '1002');
    assert.equal(page.items[0].accountTypeLabel, '银行存款');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('fund account create sends only the confirmed body and omits empty optional fields as null', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(account(), 201);
    };
    const api = await import('../src/api/fundAccountApi.ts');
    await api.createFundAccount({
      code: '',
      name: '工行基本户',
      accountType: 'BANK',
      glAccountCode: '1002',
      bankName: '工商银行',
      accountNo: '',
      extra: 'do-not-send',
    });

    assert.equal(call[0], '/api/fund/accounts');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      code: null,
      name: '工行基本户',
      accountType: 'BANK',
      glAccountCode: '1002',
      bankName: '工商银行',
      accountNo: null,
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('fund account update enable disable and leaf GL list use real write and read paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      if (String(input).startsWith('/api/gl/accounts')) {
        return response([
          {
            code: '1002',
            name: '银行存款',
            type: 'ASSET',
            typeLabel: '资产',
            leaf: true,
            enabled: true,
          },
        ]);
      }
      return response(account({ status: String(input).includes('disable') ? 'DISABLED' : 'ENABLED' }));
    };
    const api = await import('../src/api/fundAccountApi.ts');
    await api.updateFundAccount(10, {
      code: 'FA-202610-0001',
      name: '工行基本户',
      accountType: 'BANK',
      glAccountCode: '1002',
      bankName: '',
      accountNo: '6222',
    });
    const enabled = await api.enableFundAccount(10);
    const disabled = await api.disableFundAccount(10);
    const leaves = await api.listLeafGlAccounts();

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/fund/accounts/10', 'PUT'],
        ['/api/fund/accounts/10/enable', 'POST'],
        ['/api/fund/accounts/10/disable', 'POST'],
        ['/api/gl/accounts?leafOnly=true', 'GET'],
      ],
    );
    assert.deepEqual(JSON.parse(calls[0][1].body), {
      code: 'FA-202610-0001',
      name: '工行基本户',
      accountType: 'BANK',
      glAccountCode: '1002',
      bankName: null,
      accountNo: '6222',
    });
    assert.equal(enabled.status, 'ENABLED');
    assert.equal(disabled.status, 'DISABLED');
    assert.equal(leaves[0].code, '1002');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('fund account validation requires name type and enabled leaf GL code', async () => {
  const api = await import('../src/api/fundAccountApi.ts');
  const valid = {
    code: '',
    name: '库存现金',
    accountType: 'CASH',
    glAccountCode: '1001',
    bankName: '',
    accountNo: '',
  };
  assert.equal(api.validateFundAccountForm(valid, false), null);
  assert.match(api.validateFundAccountForm({ ...valid, name: '' }, false), /名称/);
  assert.match(api.validateFundAccountForm({ ...valid, glAccountCode: '' }, false), /GL 科目/);
  assert.match(api.validateFundAccountForm({ ...valid, code: '' }, true), /编码必填/);
  assert.match(
    api.validateFundAccountForm({ ...valid, accountType: 'INVALID' }, false),
    /CASH \/ BANK \/ OTHER/,
  );
});

test('fund account client and workbench expose no fabricated delete or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/fundAccountApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/FundAccountWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:删除|作废)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockFund|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\(/);
});

test('fund account tab uses real workbench and write is fail-closed on finance:payment_account', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:payment_account'\)/);
  assert.equal(appSource.includes('>资金账户</button>'), true);
  assert.equal(appSource.includes('<FundAccountWorkbench canWrite={canWriteFunds} />'), true);
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('fund account workbench has in-flight guard confirmation and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/FundAccountWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /停用后新的收付款单不得引用/);
  for (const token of ['listVersion', 'detailVersion', 'glVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /leafOnly=true/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
});
