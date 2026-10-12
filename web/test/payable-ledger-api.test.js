import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

test('payable list sends exact filters and preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response({
        items: [
          {
            id: 100,
            supplierId: 7,
            amount: 200.5,
            sourceDocNo: 'PINV-1',
            dueDate: '2026-11-01',
            status: 'PARTIAL',
            settledAmount: 50,
            outstandingAmount: 150.5,
          },
        ],
        total: 1,
        page: 2,
        size: 20,
      });
    };
    const api = await import('../src/api/payableLedgerApi.ts');
    const page = await api.searchPayables(7, 'OPEN', 2);

    assert.equal(calls[0][0], '/api/payables?page=2&size=20&supplierId=7&status=OPEN');
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(page.items[0].amount, '200.5');
    assert.equal(page.items[0].settledAmount, '50');
    assert.equal(page.items[0].outstandingAmount, '150.5');
    assert.equal(Object.hasOwn(page.items[0], 'openAmount'), false);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('payable settlements use type=PAYABLE and keep amount strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response({
        items: [
          {
            id: 9,
            type: 'PAYABLE',
            targetId: 100,
            targetSourceDocNo: 'PINV-1',
            amount: 50,
            settlementDate: '2026-10-12',
            paymentDocNo: 'PAYV-1',
            createdBy: 'accountant',
            createdAt: '2026-10-12T05:00:00Z',
          },
        ],
      });
    };
    const api = await import('../src/api/payableLedgerApi.ts');
    const settlements = await api.searchPayableSettlements(100);

    assert.equal(call[0], '/api/settlements?type=PAYABLE&targetId=100');
    assert.equal(call[1].method, 'GET');
    assert.equal(settlements[0].amount, '50');
    assert.equal(settlements[0].paymentDocNo, 'PAYV-1');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('payable ledger client has no detail GET and no write or mock paths', () => {
  const apiSource = readFileSync(
    new URL('../src/api/payableLedgerApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/PayableLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /\/api\/payables\/\$\{/);
  assert.doesNotMatch(apiSource, /method:\s*['"]POST['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]PUT['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:新建|编辑|删除|作废|过账)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /openAmount/);
  assert.doesNotMatch(combined, /demoPassword|假数据|mockPayable|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:amount|settledAmount|outstandingAmount)/);
  assert.match(workbenchSource, /outstandingAmount/);
  assert.match(workbenchSource, /本接口无独立详情端点/);
});

test('payable tab is always in finance module and settlements are fail-closed', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:settlement'\)/);
  assert.equal(appSource.includes('>应付台账</button>'), true);
  assert.equal(
    appSource.includes('<PayableLedgerWorkbench canViewSettlements={canViewSettlements} />'),
    true,
  );
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('payable ledger workbench uses list row as detail and isolates races', () => {
  const source = readFileSync(
    new URL('../src/components/PayableLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const listVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const detailVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const settlementVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const supplierNameVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const supplierVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /供应商 #\$\{id\}（名称不可用）/);
  assert.match(source, /finance:settlement/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.doesNotMatch(source, /getPayable/);
});
