import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const agingRow = (overrides = {}) => ({
  counterpartyId: 1,
  counterpartyCode: 'C001',
  counterpartyName: '示例客户',
  notDue: 0,
  overdue1To30: 100.5,
  overdue31To60: 0,
  overdue61To90: 0,
  overdue90Plus: 50,
  totalOutstanding: 150.5,
  ...overrides,
});

const grandTotal = (overrides = {}) => ({
  notDue: '0.00',
  overdue1To30: '100.50',
  overdue31To60: '0.00',
  overdue61To90: '0.00',
  overdue90Plus: '50.00',
  totalOutstanding: '150.50',
  ...overrides,
});

test('receivable aging sends exact filters and preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response({
        asOf: '2026-10-12',
        items: [agingRow()],
        total: 1,
        page: 2,
        size: 20,
        grandTotal: grandTotal({ overdue1To30: 100.5, totalOutstanding: 150.5 }),
      });
    };
    const api = await import('../src/api/agingReportApi.ts');
    const page = await api.searchReceivableAging('2026-10-12', 7, 2);

    assert.equal(
      calls[0][0],
      '/api/reports/receivable-aging?page=2&size=20&asOf=2026-10-12&customerId=7',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(page.asOf, '2026-10-12');
    assert.equal(page.items[0].overdue1To30, '100.5');
    assert.equal(page.items[0].totalOutstanding, '150.5');
    assert.equal(page.grandTotal.overdue1To30, '100.5');
    assert.equal(page.grandTotal.totalOutstanding, '150.5');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('payable aging sends supplierId and omits empty asOf and counterparty', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response({
        asOf: '2026-10-12',
        items: [agingRow({
          counterpartyCode: null,
          counterpartyName: null,
          overdue1To30: '80.00',
          overdue90Plus: '20.00',
          totalOutstanding: '100.00',
        })],
        total: 1,
        page: 1,
        size: 20,
        grandTotal: grandTotal({
          overdue1To30: '80.00',
          overdue90Plus: '20.00',
          totalOutstanding: '100.00',
        }),
      });
    };
    const api = await import('../src/api/agingReportApi.ts');
    const omitted = await api.searchPayableAging('', null, 1);
    const filtered = await api.searchPayableAging('2026-09-01', 20, 1);

    assert.equal(calls[0][0], '/api/reports/payable-aging?page=1&size=20');
    assert.equal(
      calls[1][0],
      '/api/reports/payable-aging?page=1&size=20&asOf=2026-09-01&supplierId=20',
    );
    assert.equal(omitted.items[0].counterpartyCode, null);
    assert.equal(omitted.items[0].overdue1To30, '80.00');
    assert.equal(filtered.grandTotal.totalOutstanding, '100.00');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('aging client is read-only and never invents a settlements list', () => {
  const apiSource = readFileSync(
    new URL('../src/api/agingReportApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/AgingReportWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]POST['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]PUT['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/api\/settlements/);
  assert.doesNotMatch(workbenchSource, />\s*(?:新建|编辑|删除|作废|过账|冲销)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|假数据|mockAging|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:notDue|overdue|totalOutstanding)/);
  assert.doesNotMatch(combined, /\/api\/gl\/vouchers\/[^'"]*\/reverse/);
  assert.doesNotMatch(combined, /trial-balance|period.*close|close-precheck/);
  assert.match(apiSource, /\/api\/reports\/receivable-aging/);
  assert.match(apiSource, /\/api\/reports\/payable-aging/);
  assert.match(workbenchSource, /grandTotal/);
  assert.match(workbenchSource, /对手方 #\$\{item\.counterpartyId\}（名称不可用）/);
});

test('aging tabs are fail-closed on finance:settlement and use real workbenches', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:settlement'\)/);
  assert.equal(appSource.includes('>应收账龄</button>'), true);
  assert.equal(appSource.includes('>应付账龄</button>'), true);
  assert.match(appSource, /canViewAging && <button type="button" role="tab"/);
  assert.equal(appSource.includes('<ReceivableAgingWorkbench />'), true);
  assert.equal(appSource.includes('<PayableAgingWorkbench />'), true);
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('aging workbench isolates list races and shows full-set grandTotal', () => {
  const source = readFileSync(
    new URL('../src/components/AgingReportWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const listVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /export function ReceivableAgingWorkbench/);
  assert.match(source, /export function PayableAgingWorkbench/);
  assert.match(source, /searchReceivableAging/);
  assert.match(source, /searchPayableAging/);
  assert.match(source, /全量合计/);
  assert.match(source, /未到期/);
  assert.match(source, /逾期 1-30/);
  assert.match(source, /逾期 90\+/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /finance:settlement/);
  assert.match(source, /type="date"/);
});

test('ledger settlement流水 stays per-target and has no global list', () => {
  const receivableApi = readFileSync(
    new URL('../src/api/receivableLedgerApi.ts', import.meta.url),
    'utf8',
  );
  const payableApi = readFileSync(
    new URL('../src/api/payableLedgerApi.ts', import.meta.url),
    'utf8',
  );
  const receivableUi = readFileSync(
    new URL('../src/components/ReceivableLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const payableUi = readFileSync(
    new URL('../src/components/PayableLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const agingApi = readFileSync(
    new URL('../src/api/agingReportApi.ts', import.meta.url),
    'utf8',
  );
  assert.match(receivableApi, /\/api\/settlements\?type=RECEIVABLE&targetId=\$\{targetId\}/);
  assert.match(payableApi, /\/api\/settlements\?type=PAYABLE&targetId=\$\{targetId\}/);
  assert.doesNotMatch(receivableApi, /\/api\/settlements\?(?!type=)/);
  assert.doesNotMatch(agingApi, /\/api\/settlements/);
  assert.match(receivableUi, /核销流水/);
  assert.match(payableUi, /核销流水/);
  assert.match(receivableUi, /createdBy/);
  assert.match(payableUi, /createdBy/);
  assert.doesNotMatch(`${receivableUi}\n${payableUi}`, /settle\(/);
  assert.doesNotMatch(`${receivableUi}\n${payableUi}`, /method:\s*['"]POST['"]/);
});
