import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const balanceSheet = (overrides = {}) => ({
  period: '202610',
  assetLines: [{ name: '货币资金', amount: 1500.5 }],
  totalAssets: 1500.5,
  liabilityLines: [{ name: '应付账款', amount: '200.00' }],
  totalLiabilities: '200.00',
  equityLines: [{ name: '未分配利润', amount: '1300.50' }],
  totalEquity: '1300.50',
  balanced: true,
  ...overrides,
});

const incomeStatement = (overrides = {}) => ({
  period: '202610',
  lines: [
    { name: '一、营业收入', currentPeriod: 1000, yearToDate: '6000.00' },
    { name: '减：营业成本', currentPeriod: '600.00', yearToDate: 3600 },
    { name: '四、净利润', currentPeriod: '400.00', yearToDate: '2400.00' },
  ],
  netProfitCurrent: 400,
  netProfitYtd: '2400.00',
  ...overrides,
});

test('balance sheet and income statement use exact report paths and keep amounts as strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('income-statement')
        ? response(incomeStatement())
        : response(balanceSheet());
    };
    const api = await import('../src/api/financialStatementApi.ts');
    const sheet = await api.getBalanceSheet('202610');
    const income = await api.getIncomeStatement(' 202610 ');

    assert.equal(calls[0][0], '/api/reports/balance-sheet?period=202610');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/reports/income-statement?period=202610');
    assert.equal(sheet.assetLines[0].amount, '1500.5');
    assert.equal(sheet.totalAssets, '1500.5');
    assert.equal(sheet.balanced, true);
    assert.equal(income.lines[0].currentPeriod, '1000');
    assert.equal(income.lines[0].yearToDate, '6000.00');
    assert.equal(income.netProfitCurrent, '400');
    assert.equal(income.netProfitYtd, '2400.00');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('financial statement client rejects invalid period before calling the API', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let called = 0;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async () => {
      called += 1;
      return response(balanceSheet());
    };
    const api = await import('../src/api/financialStatementApi.ts');
    assert.equal(api.validateReportPeriod('202610'), null);
    assert.match(api.validateReportPeriod(''), /账期/);
    assert.match(api.validateReportPeriod('2026'), /yyyyMM/);
    await assert.rejects(() => api.getBalanceSheet('2026AB'), /yyyyMM/);
    await assert.rejects(() => api.getIncomeStatement('202613'), /月份/);
    assert.equal(called, 0);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('financial statement client is read-only and never invents a cash-flow statement', () => {
  const apiSource = readFileSync(
    new URL('../src/api/financialStatementApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/FinancialStatementWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]POST['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]PUT['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(combined, /cash-flow|cashFlow|现金流量表/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|假数据|mockStatement|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:amount|currentPeriod|yearToDate|totalAssets|netProfit)/);
  assert.match(apiSource, /\/api\/reports\/balance-sheet/);
  assert.match(apiSource, /\/api\/reports\/income-statement/);
  assert.match(workbenchSource, /finance:report/);
});

test('report tabs are fail-closed on finance:report and use real workbenches', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:report'\)/);
  assert.equal(appSource.includes('>资产负债表</button>'), true);
  assert.equal(appSource.includes('>利润表</button>'), true);
  assert.match(appSource, /canViewReports && <button type="button" role="tab"/);
  assert.equal(appSource.includes('<BalanceSheetWorkbench />'), true);
  assert.equal(appSource.includes('<IncomeStatementWorkbench />'), true);
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
  assert.doesNotMatch(appSource, /现金流量表/);
});

test('financial statement workbench isolates races and shows amounts as strings', () => {
  const source = readFileSync(
    new URL('../src/components/FinancialStatementWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const reportVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /export function BalanceSheetWorkbench/);
  assert.match(source, /export function IncomeStatementWorkbench/);
  assert.match(source, /getBalanceSheet/);
  assert.match(source, /getIncomeStatement/);
  assert.match(source, /balanced/);
  assert.match(source, /资产总计/);
  assert.match(source, /负债合计/);
  assert.match(source, /权益合计/);
  assert.match(source, /本年累计/);
  assert.match(source, /净利润/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /finance:report/);
  assert.doesNotMatch(source, /现金流量表/);
});
