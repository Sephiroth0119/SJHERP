import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { ApiError } from '../src/api/http.ts';
import { displayApiError, formatAmountText } from '../src/ui/format.ts';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const readSrc = (relative) => readFileSync(join(webRoot, relative), 'utf8');

test('formatAmountText keeps decimal strings and never uses float math', () => {
  assert.equal(formatAmountText('12.50'), '12.50');
  assert.equal(formatAmountText('0.10'), '0.10');
  assert.equal(formatAmountText(''), '—');
  assert.equal(formatAmountText('   '), '—');
  assert.equal(formatAmountText(undefined), '—');
  assert.equal(formatAmountText(null), '—');
  const source = readSrc('src/ui/format.ts').replace(/\/\*[\s\S]*?\*\//g, '');
  assert.doesNotMatch(source, /parseFloat|Number\(/);
});

test('displayApiError prefers backend {error} text', () => {
  assert.equal(displayApiError(new ApiError('信用额度不能为负'), '保存失败'), '信用额度不能为负');
  assert.equal(displayApiError(new Error('boom'), '保存失败，请检查输入后重试'), '保存失败，请检查输入后重试');
});

test('web depends on antd 5, not Vue component libraries', () => {
  const pkg = JSON.parse(readSrc('package.json'));
  assert.match(String(pkg.dependencies.antd), /^\^?5\./);
  assert.equal(pkg.dependencies['naive-ui'], undefined);
  assert.equal(pkg.dependencies['ant-design-vue'], undefined);
  assert.equal(pkg.dependencies.vue, undefined);
});

test('theme tokens stay on the existing navy/blue palette', () => {
  const theme = readSrc('src/ui/theme.ts');
  const css = readSrc('src/styles/global.css');
  assert.match(css, /--color-primary:\s*#2563eb/);
  assert.match(css, /--color-sidebar:\s*#1f2733/);
  assert.match(theme, /colorPrimary:\s*'#2563eb'/);
  assert.match(theme, /colorTextBase:\s*'#1f2733'/);
  assert.match(theme, /colorError:\s*'#dc2626'/);
  assert.doesNotMatch(readSrc('src/main.tsx'), /antd\/dist\/reset/);
});

test('customer workbench uses table/filter/modal/drawer shells with fail-closed permissions', () => {
  const source = readSrc('src/components/CustomerWorkbench.tsx');
  assert.match(source, /permissions\.includes\('partner:create_customer'\)/);
  assert.match(source, /permissions\.includes\('partner:write'\)/);
  assert.match(source, /WorkbenchTable/);
  assert.match(source, /FilterBar/);
  assert.match(source, /AppModal/);
  assert.match(source, /AppDrawer/);
  assert.match(source, /useWorkbenchNotice/);
  assert.match(source, /displayApiError/);
  assert.match(source, /name="creditLimit"/);
  assert.doesNotMatch(source, /parseFloat|Number\(form\.creditLimit\)/);
  assert.match(source, /role="alert"/);
});

test('master data and report query tables reuse WorkbenchTable', () => {
  const master = readSrc('src/components/MasterDataWorkbench.tsx');
  const query = readSrc('src/components/report/QueryTable.tsx');
  const aging = readSrc('src/components/AgingReportWorkbench.tsx');
  const reports = readSrc('src/components/ReportWorkbench.tsx');
  assert.match(master, /WorkbenchTable/);
  assert.match(master, /FilterBar/);
  assert.match(master, /partner:create_supplier/);
  assert.match(master, /warehouse:create_warehouse/);
  assert.match(query, /WorkbenchTable/);
  assert.match(query, /formatAmountText/);
  assert.match(aging, /WorkbenchTable/);
  assert.match(aging, /formatAmountText/);
  assert.match(reports, /QueryTable/);
  assert.match(reports, /导出 Excel/);
  assert.doesNotMatch(`${master}\n${query}\n${aging}\n${reports}`, /parseFloat\s*\(/);
});

test('chat, login and sidebar stay on custom CSS instead of antd', () => {
  assert.doesNotMatch(readSrc('src/components/chat/ChatPanel.tsx'), /from 'antd'/);
  assert.doesNotMatch(readSrc('src/components/Sidebar.tsx'), /from 'antd'/);
  assert.doesNotMatch(readSrc('src/components/LoginPage.tsx'), /from 'antd'/);
  assert.match(readSrc('src/main.tsx'), /UiProvider/);
});

test('M7-T05 does not touch prebuilt web-dist', () => {
  assert.equal(existsSync(join(webRoot, 'src/ui/WorkbenchTable.tsx')), true);
  assert.doesNotMatch(readSrc('src/main.tsx'), /web-dist/);
  assert.doesNotMatch(readSrc('src/ui/index.ts'), /web-dist/);
});
