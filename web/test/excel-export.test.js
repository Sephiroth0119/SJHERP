import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('spreadsheet ML keeps every cell as string including amount-like values', async () => {
  const api = await import('../src/report/excelExport.ts');
  const xml = api.buildSpreadsheetMl([{
    name: '应收账龄',
    headers: ['客户', '合计'],
    rows: [['示例客户', '150.50'], ['全量合计', '0.00']],
  }]);
  assert.match(xml, /ss:Type="String"/);
  assert.match(xml, /150\.50/);
  assert.match(xml, /ss:Name="应收账龄"/);
  assert.doesNotMatch(xml, /ss:Type="Number"/);
  assert.equal(api.spreadsheetFilename('应收账龄', '2026-10-21'), '应收账龄-2026-10-21.xls');
});

test('excel export utility never parses amounts as float', () => {
  const source = readFileSync(new URL('../src/report/excelExport.ts', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /parseFloat\s*\(/);
  assert.doesNotMatch(source, /Number\s*\(/);
  assert.match(source, /ss:Type="String"/);
});
