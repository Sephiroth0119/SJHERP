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
