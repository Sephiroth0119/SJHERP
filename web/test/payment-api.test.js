import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const payment = (overrides = {}) => ({
  docNo: 'PAYV-202610-0001',
  supplierId: 7,
  paymentAccountId: 10,
  paymentDate: '2026-10-12',
  remark: '十月货款',
  status: 'DRAFT',
  totalAmount: '9999999999999999.99',
  lines: [{ lineNo: 1, payableId: 100, allocatedAmount: '1234567890123456.78' }],
  ...overrides,
});

test('payment list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('PAYV-202610-0001')
        ? response(payment())
        : response({
            items: [payment({ totalAmount: 12.5 })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/paymentApi.ts');
    const page = await api.searchPayments(7, 10, 'APPROVED', 3);
    const detail = await api.getPayment('PAYV-202610-0001');

    assert.equal(
      calls[0][0],
      '/api/payments?page=3&size=20&supplierId=7&paymentAccountId=10&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/payments/PAYV-202610-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].totalAmount, '12.5');
    assert.equal(detail.lines[0].allocatedAmount, '1234567890123456.78');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('payment create sends only the narrow body and keeps allocated amounts as strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(payment(), 201);
    };
    const api = await import('../src/api/paymentApi.ts');
    await api.createPayment({
      supplierId: 7,
      paymentAccountId: 10,
      paymentDate: '2026-10-12',
      remark: '保留字符串',
      status: 'APPROVED',
      audit: 'do-not-send',
      lines: [
        {
          payableId: 100,
          allocatedAmount: '9999999999999999.99',
          outstandingAmount: '9999999999999999.99',
          sourceDocNo: 'PINV-1',
        },
      ],
    });

    assert.equal(call[0], '/api/payments');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      supplierId: 7,
      paymentAccountId: 10,
      paymentDate: '2026-10-12',
      remark: '保留字符串',
      lines: [{ payableId: 100, allocatedAmount: '9999999999999999.99' }],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('payables fund accounts and payable settlements use real read paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      if (String(input).startsWith('/api/payables')) {
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
      }
      if (String(input).startsWith('/api/fund/accounts')) {
        return String(input).includes('/10')
          ? response({
              id: 10,
              code: 'FA-1',
              name: '基本户',
              accountType: 'BANK',
              status: 'ENABLED',
            })
          : response({
              items: [
                {
                  id: 10,
                  code: 'FA-1',
                  name: '基本户',
                  accountType: 'BANK',
                  status: 'ENABLED',
                },
              ],
              total: 1,
              page: 1,
              size: 20,
            });
      }
      return response({
        items: [
          {
            id: 9,
            type: 'PAYABLE',
            targetId: 100,
            amount: 50,
            paymentDocNo: 'PAYV-1',
          },
        ],
      });
    };
    const api = await import('../src/api/paymentApi.ts');
    const payables = await api.searchPayables(7, 'OPEN', 2);
    const accounts = await api.searchFundAccounts('ENABLED', 1);
    const account = await api.getFundAccount(10);
    const settlements = await api.searchPayableSettlements(100);

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/payables?page=2&size=20&supplierId=7&status=OPEN', 'GET'],
        ['/api/fund/accounts?page=1&size=20&status=ENABLED', 'GET'],
        ['/api/fund/accounts/10', 'GET'],
        ['/api/settlements?type=PAYABLE&targetId=100', 'GET'],
      ],
    );
    assert.equal(payables.items[0].amount, '200.5');
    assert.equal(payables.items[0].settledAmount, '50');
    assert.equal(payables.items[0].outstandingAmount, '150.5');
    assert.equal(accounts.items[0].name, '基本户');
    assert.equal(account.code, 'FA-1');
    assert.equal(settlements[0].amount, '50');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('approve post and reverse use only real POST action URLs', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return response(payment({ totalAmount: 12.5, lines: [{ lineNo: 1, payableId: 100, allocatedAmount: 12.5 }] }));
    };
    const api = await import('../src/api/paymentApi.ts');
    const approved = await api.approvePayment('PAYV/unsafe');
    const posted = await api.postPayment('PAYV/unsafe');
    const reversed = await api.reversePayment('PAYV/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/payments/PAYV%2Funsafe/approve', 'POST'],
        ['/api/payments/PAYV%2Funsafe/post', 'POST'],
        ['/api/payments/PAYV%2Funsafe/reverse', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
    for (const result of [approved, posted, reversed]) {
      assert.equal(result.totalAmount, '12.5');
      assert.equal(result.lines[0].allocatedAmount, '12.5');
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('payment validation is string based and rejects excess allocation duplicates and overflow', async () => {
  const api = await import('../src/api/paymentApi.ts');
  const valid = {
    supplierId: 7,
    paymentAccountId: 10,
    paymentDate: '2026-10-12',
    remark: '',
    lines: [
      {
        payableId: 100,
        allocatedAmount: '9999999999999999.99',
        outstandingAmount: '9999999999999999.99',
      },
    ],
  };
  assert.equal(api.validatePaymentForm(valid), null);
  assert.match(api.validatePaymentForm({ ...valid, supplierId: 0 }), /供应商/);
  assert.match(api.validatePaymentForm({ ...valid, paymentAccountId: 0 }), /资金账户/);
  assert.match(api.validatePaymentForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(
    api.validatePaymentForm({
      ...valid,
      lines: [{ ...valid.lines[0], allocatedAmount: '0' }],
    }),
    /分摊金额必须大于 0/,
  );
  assert.match(
    api.validatePaymentForm({
      ...valid,
      lines: [{ ...valid.lines[0], allocatedAmount: '1.001' }],
    }),
    /小数最多 2 位/,
  );
  assert.match(
    api.validatePaymentForm({
      ...valid,
      lines: [
        {
          ...valid.lines[0],
          allocatedAmount: '150.51',
          outstandingAmount: '150.50',
        },
      ],
    }),
    /超过未核销余额/,
  );
  assert.match(
    api.validatePaymentForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], allocatedAmount: '1.00' }],
    }),
    /应付不能重复/,
  );
});

test('payment client and workbench expose no fabricated cancel edit delete or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/paymentApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/PaymentWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockPayment|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:allocatedAmount|outstandingAmount|amount)/);
});

test('payment tab is fail-closed on finance:settlement and uses real workbench', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:settlement'\)/);
  assert.equal(appSource.includes('>付款</button>}'), true);
  assert.equal(appSource.includes('<PaymentWorkbench focusDocNo={docNo} />'), true);
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('payment workbench has in-flight guard post reverse confirmation and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/PaymentWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /核销应付/);
  assert.match(source, /现金侧凭证/);
  assert.match(source, /整单回滚/);
  assert.match(source, /反向核销/);
  for (const token of [
    'listVersion',
    'detailVersion',
    'payableVersion',
    'accountVersion',
    'settlementVersion',
    'supplierNameVersion',
    'accountNameVersion',
  ]) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /供应商 #\$\{id\}（名称不可用）/);
  assert.match(source, /资金账户 #\$\{id\}（名称不可用）/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
});
