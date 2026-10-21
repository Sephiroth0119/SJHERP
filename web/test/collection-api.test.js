import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const collection = (overrides = {}) => ({
  docNo: 'RCPT-202610-0001',
  customerId: 7,
  paymentAccountId: 10,
  receiptDate: '2026-10-12',
  remark: '十月回款',
  status: 'DRAFT',
  totalAmount: '9999999999999999.99',
  lines: [{ lineNo: 1, receivableId: 200, allocatedAmount: '1234567890123456.78' }],
  ...overrides,
});

test('collection list sends exact filters and detail preserves decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('RCPT-202610-0001')
        ? response(collection())
        : response({
            items: [collection({ totalAmount: 12.5 })],
            total: 1,
            page: 3,
            size: 20,
          });
    };
    const api = await import('../src/api/collectionApi.ts');
    const page = await api.searchCollections(7, 10, 'APPROVED', 3);
    const detail = await api.getCollection('RCPT-202610-0001');

    assert.equal(
      calls[0][0],
      '/api/collections?page=3&size=20&customerId=7&paymentAccountId=10&status=APPROVED',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/collections/RCPT-202610-0001');
    assert.equal(page.items[0].totalAmount, '12.5');
    assert.equal(detail.lines[0].allocatedAmount, '1234567890123456.78');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('collection create sends only the narrow body and keeps allocated amounts as strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(collection(), 201);
    };
    const api = await import('../src/api/collectionApi.ts');
    await api.createCollection({
      customerId: 7,
      paymentAccountId: 10,
      receiptDate: '2026-10-12',
      remark: '保留字符串',
      status: 'APPROVED',
      audit: 'do-not-send',
      lines: [
        {
          receivableId: 200,
          allocatedAmount: '9999999999999999.99',
          openAmount: '9999999999999999.99',
          sourceDocNo: 'SINV-1',
        },
      ],
    });

    assert.equal(call[0], '/api/collections');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      customerId: 7,
      paymentAccountId: 10,
      receiptDate: '2026-10-12',
      remark: '保留字符串',
      lines: [{ receivableId: 200, allocatedAmount: '9999999999999999.99' }],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('receivables fund accounts and receivable settlements use real read paths', async () => {
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
      if (String(input).startsWith('/api/receivables')) {
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
      }
      if (String(input).startsWith('/api/fund/accounts')) {
        return response({
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
            type: 'RECEIVABLE',
            targetId: 200,
            amount: 50,
            paymentDocNo: 'RCPT-1',
          },
        ],
      });
    };
    const api = await import('../src/api/collectionApi.ts');
    const receivables = await api.searchReceivables(7, 'OPEN', 2);
    const detail = await api.getReceivable(200);
    const accounts = await api.searchFundAccounts('ENABLED', 1);
    const settlements = await api.searchReceivableSettlements(200);

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/receivables?page=2&size=20&customerId=7&status=OPEN', 'GET'],
        ['/api/receivables/200', 'GET'],
        ['/api/fund/accounts?page=1&size=20&status=ENABLED', 'GET'],
        ['/api/settlements?type=RECEIVABLE&targetId=200', 'GET'],
      ],
    );
    assert.equal(receivables.items[0].openAmount, '150.5');
    assert.equal(detail.amount, '200.5');
    assert.equal(accounts.items[0].name, '基本户');
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
      return response(collection({
        totalAmount: 12.5,
        lines: [{ lineNo: 1, receivableId: 200, allocatedAmount: 12.5 }],
      }));
    };
    const api = await import('../src/api/collectionApi.ts');
    await api.approveCollection('RCPT/unsafe');
    await api.postCollection('RCPT/unsafe');
    await api.reverseCollection('RCPT/unsafe');

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/collections/RCPT%2Funsafe/approve', 'POST'],
        ['/api/collections/RCPT%2Funsafe/post', 'POST'],
        ['/api/collections/RCPT%2Funsafe/reverse', 'POST'],
      ],
    );
    assert.equal(calls.some(([, init]) => init.method === 'DELETE'), false);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('collection validation is string based and rejects excess allocation duplicates and overflow', async () => {
  const api = await import('../src/api/collectionApi.ts');
  const valid = {
    customerId: 7,
    paymentAccountId: 10,
    receiptDate: '2026-10-12',
    remark: '',
    lines: [
      {
        receivableId: 200,
        allocatedAmount: '9999999999999999.99',
        openAmount: '9999999999999999.99',
      },
    ],
  };
  assert.equal(api.validateCollectionForm(valid), null);
  assert.match(api.validateCollectionForm({ ...valid, customerId: 0 }), /客户/);
  assert.match(api.validateCollectionForm({ ...valid, paymentAccountId: 0 }), /资金账户/);
  assert.match(api.validateCollectionForm({ ...valid, lines: [] }), /至少要有一行/);
  assert.match(
    api.validateCollectionForm({
      ...valid,
      lines: [{ ...valid.lines[0], allocatedAmount: '0' }],
    }),
    /分摊金额必须大于 0/,
  );
  assert.match(
    api.validateCollectionForm({
      ...valid,
      lines: [{ ...valid.lines[0], allocatedAmount: '1.001' }],
    }),
    /小数最多 2 位/,
  );
  assert.match(
    api.validateCollectionForm({
      ...valid,
      lines: [
        {
          ...valid.lines[0],
          allocatedAmount: '150.51',
          openAmount: '150.50',
        },
      ],
    }),
    /超过未核销余额/,
  );
  assert.match(
    api.validateCollectionForm({
      ...valid,
      lines: [valid.lines[0], { ...valid.lines[0], allocatedAmount: '1.00' }],
    }),
    /应收不能重复/,
  );
});

test('collection client and workbench expose no fabricated cancel edit delete or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/collectionApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/CollectionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废)\s*</);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockCollection|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:allocatedAmount|openAmount|amount)/);
});

test('collection tab is fail-closed on finance:settlement and uses real workbench', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:settlement'\)/);
  assert.equal(appSource.includes('>收款</button>}'), true);
  assert.equal(appSource.includes('<CollectionWorkbench focusDocNo={docNo} />'), true);
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('collection workbench has in-flight guard post reverse confirmation and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/CollectionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.match(source, /window\.confirm/);
  assert.match(source, /核销应收/);
  assert.match(source, /现金侧凭证/);
  assert.match(source, /整单回滚/);
  assert.match(source, /反向核销/);
  for (const token of [
    'listVersion',
    'detailVersion',
    'receivableVersion',
    'accountVersion',
    'settlementVersion',
    'customerNameVersion',
    'accountNameVersion',
  ]) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /客户 #\$\{id\}（名称不可用）/);
  assert.match(source, /资金账户 #\$\{id\}（名称不可用）/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
});
