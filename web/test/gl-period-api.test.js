import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const period = (overrides = {}) => ({
  period: '202610',
  year: 2026,
  month: 10,
  status: 'OPEN',
  statusLabel: '开启',
  closedBy: null,
  closedAt: null,
  ...overrides,
});

const readiness = (overrides = {}) => ({
  period: '202610',
  status: 'OPEN',
  closeable: true,
  alreadyClosed: false,
  consistencyErrors: [],
  consistencyWarnings: ['三单数量越界'],
  closingPreviewLines: [
    { accountCode: '6001', accountName: '主营业务收入', debit: 1000, credit: '0.00' },
    { accountCode: '4103', accountName: '本年利润', debit: '0.00', credit: 1000 },
  ],
  totalRevenue: 1000,
  totalExpense: '600.00',
  netProfit: '400.00',
  trialBalanceDebit: '5000.00',
  trialBalanceCredit: '5000.00',
  ...overrides,
});

const closeResult = (overrides = {}) => ({
  period: '202610',
  closingVoucherDocNo: 'VCH-202610-0009',
  totalRevenue: 1000,
  totalExpense: '600.00',
  netProfit: '400.00',
  trialBalanceDebit: '5000.00',
  trialBalanceCredit: '5000.00',
  closedBy: 'accountant',
  closedAt: '2026-10-31T00:00:00Z',
  ...overrides,
});

test('period list detail and open use exact paths and keep yyyyMM keys', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input) === '/api/gl/periods'
        ? response([period(), period({ period: '202611' })])
        : response(period());
    };
    const api = await import('../src/api/glPeriodApi.ts');
    const items = await api.listPeriods();
    const detail = await api.getPeriod('202610');
    await api.openPeriod('202612');

    assert.equal(calls[0][0], '/api/gl/periods');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/gl/periods/202610');
    assert.equal(calls[2][0], '/api/gl/periods');
    assert.equal(calls[2][1].method, 'POST');
    assert.deepEqual(JSON.parse(calls[2][1].body), { period: '202612' });
    assert.equal(items[0].status, 'OPEN');
    assert.equal(detail.period, '202610');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('period precheck close and reopen preserve decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      if (String(input).includes('close-precheck')) return response(readiness());
      if (String(input).includes('/close')) return response(closeResult());
      return response(period({ status: 'OPEN', closedBy: null }));
    };
    const api = await import('../src/api/glPeriodApi.ts');
    const preview = await api.precheckPeriodClose('2026/10');
    const closed = await api.closePeriod('202610');
    const reopened = await api.reopenPeriod('202610');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/gl/periods/2026%2F10/close-precheck', 'GET'],
        ['/api/gl/periods/202610/close', 'POST'],
        ['/api/gl/periods/202610/reopen', 'POST'],
      ],
    );
    assert.equal(preview.totalRevenue, '1000');
    assert.equal(preview.closingPreviewLines[0].debit, '1000');
    assert.equal(preview.closingPreviewLines[1].credit, '1000');
    assert.equal(closed.netProfit, '400.00');
    assert.equal(closed.closingVoucherDocNo, 'VCH-202610-0009');
    assert.equal(reopened.status, 'OPEN');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('period close surfaces 409 reasons from the API error body', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async () => response({
      error: '账期[202610] 存在 2 项数据一致性错误',
      reasons: [
        '[LEDGER_QUANTITY] 库存账实不平',
        '[PAYABLE_AMOUNT] 应付勾稽不平',
      ],
    }, 409);
    const { ApiError } = await import('../src/api/http.ts');
    const api = await import('../src/api/glPeriodApi.ts');
    await assert.rejects(
      () => api.closePeriod('202610'),
      (cause) => {
        assert.ok(cause instanceof ApiError);
        assert.equal(cause.status, 409);
        assert.match(cause.message, /数据一致性错误/);
        assert.deepEqual(cause.reasons, [
          '[LEDGER_QUANTITY] 库存账实不平',
          '[PAYABLE_AMOUNT] 应付勾稽不平',
        ]);
        return true;
      },
    );
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('period validation accepts only yyyyMM keys', async () => {
  const api = await import('../src/api/glPeriodApi.ts');
  assert.equal(api.validatePeriodKey('202610'), null);
  assert.match(api.validatePeriodKey(''), /账期/);
  assert.match(api.validatePeriodKey('2026'), /yyyyMM/);
  assert.match(api.validatePeriodKey('2026AB'), /yyyyMM/);
  assert.match(api.validatePeriodKey('202613'), /月份/);
});

test('period client and workbench expose no delete mock or cash-flow detour', () => {
  const apiSource = readFileSync(
    new URL('../src/api/glPeriodApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/PeriodCloseWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]PUT['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:删除|作废|冲销)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|假数据|mockPeriod|MOCK_/);
  assert.doesNotMatch(combined, /cash-flow|现金流量表/);
  assert.match(apiSource, /\/api\/gl\/periods/);
  assert.match(apiSource, /close-precheck/);
  assert.match(workbenchSource, /finance:period/);
});

test('period tab is fail-closed on finance:period and reopen stays on period_reopen', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:period'\)/);
  assert.match(appSource, /permissions\.includes\('finance:period_reopen'\)/);
  assert.equal(appSource.includes('>账期关账</button>'), true);
  assert.match(appSource, /canManagePeriods && <button type="button" role="tab"/);
  assert.equal(
    appSource.includes('<PeriodCloseWorkbench canClose={canManagePeriods} canReopen={canReopenPeriods} />'),
    true,
  );
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('period workbench has precheck in-page close confirm reasons and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/PeriodCloseWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /const listVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /precheckPeriodClose/);
  assert.match(source, /closePeriod/);
  assert.match(source, /reopenPeriod/);
  assert.match(source, /role="alertdialog"/);
  assert.match(source, /确认关账/);
  assert.match(source, /本年利润 4103/);
  assert.match(source, /reasons/);
  assert.match(source, /consistencyErrors/);
  assert.match(source, /canReopen/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.doesNotMatch(source, /window\.confirm/);
});
