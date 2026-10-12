import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

test('receivable list and detail send exact paths and preserve openAmount strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      if (String(input).startsWith('/api/receivables/200')) {
        return response({
          id: 200,
          customerId: 7,
          amount: 200.5,
          settledAmount: 50,
          openAmount: 150.5,
          sourceDocNo: 'SINV-1',
          dueDate: '2026-11-01',
          status: 'PARTIAL',
        });
      }
      return response({
        items: [
          {
            id: 200,
            customerId: 7,
            amount: 200.5,
            settledAmount: 50,
            openAmount: 150.5,
            sourceDocNo: 'SINV-1',
            dueDate: '2026-11-01',
            status: 'PARTIAL',
          },
        ],
        total: 1,
        page: 2,
        size: 20,
      });
    };
    const api = await import('../src/api/receivableLedgerApi.ts');
    const page = await api.searchReceivables(7, 'OPEN', 2);
    const detail = await api.getReceivable(200);

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/receivables?page=2&size=20&customerId=7&status=OPEN', 'GET'],
        ['/api/receivables/200', 'GET'],
      ],
    );
    assert.equal(page.items[0].openAmount, '150.5');
    assert.equal(detail.amount, '200.5');
    assert.equal(detail.settledAmount, '50');
    assert.equal(Object.hasOwn(detail, 'outstandingAmount'), false);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('receivable settlements use type=RECEIVABLE and keep amount strings', async () => {
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
            type: 'RECEIVABLE',
            targetId: 200,
            targetSourceDocNo: 'SINV-1',
            amount: 50,
            settlementDate: '2026-10-12',
            paymentDocNo: 'RCPT-1',
            createdBy: 'accountant',
            createdAt: '2026-10-12T05:00:00Z',
          },
        ],
      });
    };
    const api = await import('../src/api/receivableLedgerApi.ts');
    const settlements = await api.searchReceivableSettlements(200);

    assert.equal(call[0], '/api/settlements?type=RECEIVABLE&targetId=200');
    assert.equal(settlements[0].amount, '50');
    assert.equal(settlements[0].paymentDocNo, 'RCPT-1');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('receivable ledger client has no write paths and does not mix outstandingAmount', () => {
  const apiSource = readFileSync(
    new URL('../src/api/receivableLedgerApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/ReceivableLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]POST['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]PUT['"]/);
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(workbenchSource, />\s*(?:新建|编辑|删除|作废|过账)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /outstandingAmount/);
  assert.doesNotMatch(combined, /demoPassword|假数据|mockReceivable|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:amount|settledAmount|openAmount)/);
  assert.match(workbenchSource, /openAmount/);
  assert.match(apiSource, /\/api\/receivables\/\$\{id\}/);
});

test('receivable tab is fail-closed on sales:invoice and uses real workbench', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('sales:invoice'\)/);
  assert.equal(appSource.includes('>应收台账</button>}'), true);
  assert.equal(
    appSource.includes('<ReceivableLedgerWorkbench canViewSettlements={canViewSettlements} />'),
    true,
  );
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('receivable ledger workbench loads detail and isolates races', () => {
  const source = readFileSync(
    new URL('../src/components/ReceivableLedgerWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const listVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const detailVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const settlementVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const customerNameVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /const customerVersion = useRef\(createRequestGate\(\)\)/);
  assert.match(source, /客户 #\$\{id\}（名称不可用）/);
  assert.match(source, /sales:invoice/);
  assert.match(source, /finance:settlement/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /getReceivable/);
});
