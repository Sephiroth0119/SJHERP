import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import {
  canViewReport,
  resolveReportKind,
  visibleReportKinds,
} from '../src/report/reportKinds.ts';

test('report kinds fail-closed to the matching REST permission', () => {
  assert.deepEqual(visibleReportKinds([]).sort(), [
    'inventory-balance',
    'stock-movement',
  ]);
  assert.equal(canViewReport('receivable-aging', []), false);
  assert.equal(canViewReport('receivable-aging', ['finance:settlement']), true);
  assert.equal(canViewReport('trial-balance', ['finance:report']), false);
  assert.equal(canViewReport('trial-balance', ['finance:voucher']), true);
  assert.equal(canViewReport('balance-sheet', ['finance:report']), true);
  assert.equal(canViewReport('purchase-detail', ['purchase:order']), true);
  assert.equal(canViewReport('sales-detail', ['sales:order']), true);
  assert.equal(resolveReportKind('balance-sheet', ['finance:settlement']), 'inventory-balance');
  assert.equal(resolveReportKind('trial-balance', ['finance:voucher']), 'trial-balance');
});

test('report workbench is a read-only generic table with excel export and charts', () => {
  const workbench = readFileSync(new URL('../src/components/ReportWorkbench.tsx', import.meta.url), 'utf8');
  const loader = readFileSync(new URL('../src/report/loadReport.ts', import.meta.url), 'utf8');
  const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  const combined = `${workbench}\n${loader}`;
  assert.match(appSource, /<ReportWorkbench/);
  assert.match(appSource, /guardedModule === 'reports'/);
  assert.match(workbench, /导出 Excel/);
  assert.match(workbench, /role="alert"/);
  assert.match(workbench, /QueryTable/);
  assert.match(workbench, /BarChart/);
  assert.match(loader, /\/api\/reports\/receivable-aging|searchReceivableAging/);
  assert.match(loader, /getTrialBalance/);
  assert.match(loader, /getAccountBalance/);
  assert.match(loader, /getBalanceSheet/);
  assert.match(loader, /getIncomeStatement/);
  assert.doesNotMatch(combined, /parseFloat\s*\(/);
  assert.doesNotMatch(combined, /method:\s*['"]POST['"]/);
  assert.doesNotMatch(combined, /web-dist/);
  assert.doesNotMatch(workbench, />\s*(?:新建|编辑|删除|过账|冲销)\s*</);
});

test('finance aging and statements also expose export and empty-safe charts', () => {
  const aging = readFileSync(new URL('../src/components/AgingReportWorkbench.tsx', import.meta.url), 'utf8');
  const statements = readFileSync(
    new URL('../src/components/FinancialStatementWorkbench.tsx', import.meta.url),
    'utf8',
  );
  assert.match(aging, /导出 Excel/);
  assert.match(aging, /账龄分桶/);
  assert.match(aging, /暂无账龄金额可绘图/);
  assert.match(statements, /导出 Excel/);
  assert.match(statements, /净利润本期 vs 本年累计/);
  assert.doesNotMatch(aging, /parseFloat\s*\(/);
  assert.doesNotMatch(statements, /parseFloat\s*\(/);
});
