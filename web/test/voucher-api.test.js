import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storage = { getItem: () => null, setItem() {}, removeItem() {} };
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const voucher = (overrides = {}) => ({
  docNo: 'VCH-202610-0001',
  period: '202610',
  voucherDate: '2026-10-12',
  word: '记',
  totalAmount: '9999999999999999.99',
  summary: '计提费用',
  sourceDocNo: null,
  sourceDocType: null,
  status: 'DRAFT',
  lines: [
    { lineNo: 1, accountCode: '6401', debit: '9999999999999999.99', credit: '0.00', summary: '费用' },
    { lineNo: 2, accountCode: '1002', debit: '0.00', credit: '9999999999999999.99', summary: '银行' },
  ],
  ...overrides,
});

test('voucher list and detail use exact query paths and preserve decimal strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      return String(input).includes('VCH-202610-0001')
        ? response(voucher())
        : response({
            items: [voucher({ totalAmount: 12.5, lines: [
              { lineNo: 1, accountCode: '6401', debit: 12.5, credit: 0, summary: '费用' },
              { lineNo: 2, accountCode: '1002', debit: 0, credit: 12.5, summary: '银行' },
            ] })],
            total: 1,
            page: 2,
            size: 20,
          });
    };
    const api = await import('../src/api/voucherApi.ts');
    const page = await api.searchVouchers('202610', 'DRAFT', 2);
    const detail = await api.getVoucher('VCH-202610-0001');

    assert.equal(
      calls[0][0],
      '/api/gl/vouchers?page=2&size=20&period=202610&status=DRAFT',
    );
    assert.equal(calls[0][1].method, 'GET');
    assert.equal(calls[1][0], '/api/gl/vouchers/VCH-202610-0001');
    assert.equal(calls[1][1].method, 'GET');
    assert.equal(page.items[0].totalAmount, '12.5');
    assert.equal(page.items[0].lines[0].debit, '12.5');
    assert.equal(page.items[0].lines[1].credit, '12.5');
    assert.equal(detail.lines[0].debit, '9999999999999999.99');
    assert.equal(detail.word, '记');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('voucher create sends only the confirmed body and keeps amounts as strings', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  let call;
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      call = [String(input), init];
      return response(voucher(), 201);
    };
    const api = await import('../src/api/voucherApi.ts');
    await api.createVoucher({
      voucherDate: '2026-10-12',
      summary: '计提费用',
      word: '记',
      period: '202610',
      sourceDocNo: 'should-not-send',
      lines: [
        { accountCode: '6401', debit: '9999999999999999.99', credit: '', summary: '费用' },
        { accountCode: '1002', debit: '', credit: '9999999999999999.99', summary: '' },
      ],
    });

    assert.equal(call[0], '/api/gl/vouchers');
    assert.equal(call[1].method, 'POST');
    assert.deepEqual(JSON.parse(call[1].body), {
      voucherDate: '2026-10-12',
      summary: '计提费用',
      lines: [
        { accountCode: '6401', debit: '9999999999999999.99', credit: '0', summary: '费用' },
        { accountCode: '1002', debit: '0', credit: '9999999999999999.99', summary: null },
      ],
    });
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('voucher post and leaf GL list use real write and read paths', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  try {
    globalThis.localStorage = storage;
    globalThis.fetch = async (input, init = {}) => {
      calls.push([String(input), init]);
      if (String(input).startsWith('/api/gl/accounts')) {
        return response([
          { code: '1002', name: '银行存款', type: 'ASSET', typeLabel: '资产', leaf: true, enabled: true },
        ]);
      }
      return response(voucher({ status: 'APPROVED', totalAmount: 12.5 }));
    };
    const api = await import('../src/api/voucherApi.ts');
    const posted = await api.postVoucher('VCH/unsafe');
    const leaves = await api.listLeafGlAccounts();

    assert.deepEqual(
      calls.map(([url, init]) => [url, init.method]),
      [
        ['/api/gl/vouchers/VCH%2Funsafe/post', 'POST'],
        ['/api/gl/accounts?leafOnly=true', 'GET'],
      ],
    );
    assert.equal(posted.status, 'APPROVED');
    assert.equal(posted.totalAmount, '12.5');
    assert.equal(leaves[0].code, '1002');
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('voucher validation is string based and requires balanced debit credit lines', async () => {
  const api = await import('../src/api/voucherApi.ts');
  const valid = {
    voucherDate: '2026-10-12',
    summary: '计提',
    lines: [
      { accountCode: '6401', debit: '100.50', credit: '', summary: '费用' },
      { accountCode: '1002', debit: '', credit: '100.50', summary: '' },
    ],
  };
  assert.equal(api.validateVoucherForm(valid), null);
  assert.deepEqual(api.sumVoucherForm(valid), {
    totalDebit: '100.50',
    totalCredit: '100.50',
    difference: '0.00',
    balanced: true,
  });
  assert.match(api.validateVoucherForm({ ...valid, voucherDate: '' }), /凭证日期/);
  assert.match(api.validateVoucherForm({ ...valid, lines: [valid.lines[0]] }), /至少要有 2 行/);
  assert.match(
    api.validateVoucherForm({
      ...valid,
      lines: [
        { ...valid.lines[0], debit: '100.50', credit: '10.00' },
        valid.lines[1],
      ],
    }),
    /恰好借或贷一方/,
  );
  assert.match(
    api.validateVoucherForm({
      ...valid,
      lines: [
        { ...valid.lines[0], debit: '1.001' },
        valid.lines[1],
      ],
    }),
    /小数最多 2 位/,
  );
  assert.match(
    api.validateVoucherForm({
      ...valid,
      lines: [
        { accountCode: '6401', debit: '100.51', credit: '', summary: '' },
        { accountCode: '1002', debit: '', credit: '100.50', summary: '' },
      ],
    }),
    /借贷不平/,
  );
  assert.match(
    api.validateVoucherForm({
      ...valid,
      lines: [
        { accountCode: '', debit: '100.50', credit: '', summary: '' },
        valid.lines[1],
      ],
    }),
    /科目/,
  );
});

test('voucher client and workbench expose no reverse delete edit or mock data', () => {
  const apiSource = readFileSync(
    new URL('../src/api/voucherApi.ts', import.meta.url),
    'utf8',
  );
  const workbenchSource = readFileSync(
    new URL('../src/components/VoucherWorkbench.tsx', import.meta.url),
    'utf8',
  );
  const combined = `${apiSource}\n${workbenchSource}`;
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/);
  assert.doesNotMatch(apiSource, /\/(?:edit|delete|cancel|reverse)(?:['"`/])/);
  assert.doesNotMatch(workbenchSource, />\s*(?:编辑|删除|作废|冲销)\s*</);
  assert.doesNotMatch(workbenchSource, /window\.confirm/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /demoPassword|password123|假数据|mockVoucher|MOCK_/);
  assert.doesNotMatch(combined, /Number\s*\([^)]*(?:debit|credit|totalAmount|difference)/);
});

test('voucher tab is fail-closed on finance:voucher and uses real workbench', () => {
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(appSource, /permissions\.includes\('finance:voucher'\)/);
  assert.equal(appSource.includes('>凭证</button>'), true);
  assert.match(appSource, /canUseVouchers && <button type="button" role="tab"/);
  assert.equal(appSource.includes('<VoucherWorkbench canWrite={canUseVouchers} focusDocNo={docNo} />'), true);
  assert.doesNotMatch(appSource, /Role\.ACCOUNTANT|roles\.includes/);
});

test('voucher workbench has in-flight guard in-page post confirm and race isolation', () => {
  const source = readFileSync(
    new URL('../src/components/VoucherWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(source, /const mutationInFlight = useRef\(createInFlightGuard\(\)\)/);
  assert.match(source, /mutationInFlight\.current\.tryAcquire\(\)/);
  assert.match(source, /mutationInFlight\.current\.release\(\)/);
  assert.doesNotMatch(source, /window\.confirm/);
  assert.match(source, /role="alertdialog"/);
  assert.match(source, /确认过账/);
  assert.match(source, /DRAFT→APPROVED/);
  assert.match(source, /账期已关/);
  for (const token of ['listVersion', 'detailVersion', 'glVersion']) {
    assert.match(source, new RegExp(token));
  }
  assert.match(source, /leafOnly=true|listLeafGlAccounts/);
  assert.match(source, /Σ借/);
  assert.match(source, /Σ贷/);
  assert.match(source, /role="alert"/);
  assert.match(source, /role="status"/);
  assert.match(source, /postConfirming/);
  assert.match(source, /onCancelPost/);
});
