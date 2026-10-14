import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const unicodeEscape = /\\u[0-9a-fA-F]{4}/;
const garbledMarker = /鏉|锛\?/;

test('master data workbench uses real UTF-8 Chinese, not escaped or garbled literals', () => {
  const source = readFileSync(
    new URL('../src/components/MasterDataWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(source, unicodeEscape);
  assert.doesNotMatch(source, garbledMarker);
  assert.match(source, /查找、维护日常业务使用的基础资料。/);
  assert.match(source, /选择左侧记录查看详情。/);
  assert.match(source, /共 \{total\} 条 · 第 \{page\} 页/);
  assert.match(source, /供应商档案/);
  assert.match(source, /仓库档案/);
});

test('top-bar role labels are real UTF-8 Chinese', () => {
  const source = readFileSync(
    new URL('../src/api/authApi.ts', import.meta.url),
    'utf8',
  );
  assert.doesNotMatch(source, unicodeEscape);
  assert.match(source, /ADMIN:\s*'管理员'/);
  assert.match(source, /BOSS:\s*'老板'/);
  assert.match(source, /ACCOUNTANT:\s*'会计'/);
  assert.match(source, /WAREHOUSE:\s*'仓管'/);
  assert.match(source, /PURCHASER:\s*'采购'/);
  assert.match(source, /SALES:\s*'销售'/);
  assert.match(source, /join\('、'\)/);
});

test('Vite production build emits UTF-8 instead of escaped ASCII', () => {
  const source = readFileSync(new URL('../vite.config.ts', import.meta.url), 'utf8');
  assert.match(source, /charset:\s*['"]utf8['"]/);
});

test('finance workbenches use real UTF-8 Chinese, not escaped or garbled literals', () => {
  const files = [
    '../src/components/FundAccountWorkbench.tsx',
    '../src/components/PayableLedgerWorkbench.tsx',
    '../src/components/ReceivableLedgerWorkbench.tsx',
    '../src/components/AgingReportWorkbench.tsx',
    '../src/components/VoucherWorkbench.tsx',
  ];
  for (const file of files) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(source, unicodeEscape);
    assert.doesNotMatch(source, garbledMarker);
  }
  const funds = readFileSync(new URL('../src/components/FundAccountWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(funds, /资金账户/);
  assert.match(funds, /映射 GL 科目/);
  const payables = readFileSync(new URL('../src/components/PayableLedgerWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(payables, /应付台账/);
  assert.match(payables, /核销流水/);
  const receivables = readFileSync(new URL('../src/components/ReceivableLedgerWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(receivables, /应收台账/);
  assert.match(receivables, /openAmount/);
  const aging = readFileSync(new URL('../src/components/AgingReportWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(aging, /应收账龄/);
  assert.match(aging, /应付账龄/);
  assert.match(aging, /全量合计/);
  const vouchers = readFileSync(new URL('../src/components/VoucherWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(vouchers, /记账凭证/);
  assert.match(vouchers, /确认过账/);
});

test('production workbenches use real UTF-8 Chinese, not escaped or garbled literals', () => {
  const files = [
    '../src/components/ProductionWorkbench.tsx',
    '../src/components/BomWorkbench.tsx',
    '../src/components/RoutingWorkbench.tsx',
  ];
  for (const file of files) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(source, unicodeEscape);
    assert.doesNotMatch(source, garbledMarker);
  }
  const production = readFileSync(
    new URL('../src/components/ProductionWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(production, /生产工作台/);
  assert.match(production, /工艺路线/);
  const boms = readFileSync(new URL('../src/components/BomWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(boms, /物料清单/);
  assert.match(boms, /损耗率/);
  const routings = readFileSync(
    new URL('../src/components/RoutingWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(routings, /工艺路线/);
  assert.match(routings, /标准工时/);
});

test('inventory workbenches use real UTF-8 Chinese, not escaped or garbled literals', () => {
  const files = [
    '../src/components/StockCountWorkbench.tsx',
    '../src/components/TransferWorkbench.tsx',
    '../src/components/InventoryLedgerWorkbench.tsx',
  ];
  for (const file of files) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(source, unicodeEscape);
    assert.doesNotMatch(source, garbledMarker);
  }
  const count = readFileSync(new URL('../src/components/StockCountWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(count, /库存盘点/);
  assert.match(count, /录入本行/);
  const transfer = readFileSync(new URL('../src/components/TransferWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(transfer, /库存调拨/);
  assert.match(transfer, /金额守恒/);
  const ledger = readFileSync(new URL('../src/components/InventoryLedgerWorkbench.tsx', import.meta.url), 'utf8');
  assert.match(ledger, /库存台账/);
  assert.match(ledger, /加权单价/);
});
