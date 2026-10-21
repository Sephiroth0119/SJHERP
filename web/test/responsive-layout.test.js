import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { COMPACT_CHAT_MEDIA, COMPACT_SHELL_MEDIA } from '../src/layout/useMediaQuery.ts';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const readSrc = (relative) => readFileSync(join(webRoot, relative), 'utf8');

test('compact breakpoints cover phone and tablet shells', () => {
  assert.equal(COMPACT_SHELL_MEDIA, '(max-width: 1023px)');
  assert.equal(COMPACT_CHAT_MEDIA, '(max-width: 767px)');
});

test('app shell drawers the sidebar below 1024 without rewriting navigation to antd Menu', () => {
  const app = readSrc('src/App.tsx');
  const sidebar = readSrc('src/components/Sidebar.tsx');
  const css = readSrc('src/styles/global.css');
  const hookIndex = app.indexOf('useMediaQuery(COMPACT_SHELL_MEDIA)');
  const earlyReturnIndex = app.indexOf('  if (!user) {');
  assert.ok(hookIndex >= 0);
  assert.ok(hookIndex < earlyReturnIndex, 'compact layout hook must stay above the login early return');
  assert.match(app, /className="app-nav-toggle"/);
  assert.match(app, /aria-controls="app-sidebar"/);
  assert.match(app, /className="app-nav-backdrop"/);
  assert.match(app, /setNavOpen\(false\)/);
  assert.match(app, /app-nav-open/);
  assert.match(sidebar, /id="app-sidebar"/);
  assert.doesNotMatch(sidebar, /from 'antd'/);
  assert.doesNotMatch(sidebar, /Layout|Menu/);
  assert.match(css, /@media \(max-width:\s*1023px\)/);
  assert.match(css, /\.app-nav-open \.sidebar/);
  assert.match(css, /transform:\s*translateX\(-100%\)/);
});

test('workbench tables scroll horizontally and archive lists hide secondary columns on phones', () => {
  const table = readSrc('src/ui/WorkbenchTable.tsx');
  const customer = readSrc('src/components/CustomerWorkbench.tsx');
  const master = readSrc('src/components/MasterDataWorkbench.tsx');
  const query = readSrc('src/components/report/QueryTable.tsx');
  assert.match(table, /scroll=\{\{\s*x:\s*'max-content'\s*\}\}/);
  assert.match(customer, /responsive:\s*\['md'\]/);
  assert.match(master, /responsive:\s*\['md'\]/);
  assert.match(query, /WorkbenchTable/);
  assert.match(query, /formatAmountText/);
  assert.doesNotMatch(`${customer}\n${master}\n${query}`, /parseFloat\s*\(/);
});

test('filter bars wrap on narrow screens and drawer/modal stay inside the viewport', () => {
  const css = readSrc('src/styles/global.css');
  const customer = readSrc('src/components/CustomerWorkbench.tsx');
  const master = readSrc('src/components/MasterDataWorkbench.tsx');
  const drawer = readSrc('src/ui/AppDrawer.tsx');
  const modal = readSrc('src/ui/AppModal.tsx');
  const filter = readSrc('src/ui/FilterBar.tsx');
  assert.match(css, /\.workbench-filter-bar/);
  assert.match(css, /@media \(max-width:\s*640px\)/);
  assert.match(customer, /className="workbench-filter-input"/);
  assert.match(master, /className="workbench-filter-input"/);
  assert.match(filter, /workbench-filter-bar/);
  assert.match(drawer, /min\(\$\{width\}px, 100vw\)/);
  assert.match(modal, /min\(\$\{width\}px, calc\(100vw - 24px\)\)/);
});

test('chat and login remain usable on phone widths without importing antd', () => {
  const chat = readSrc('src/components/chat/ChatPanel.tsx');
  const loginCss = readSrc('src/components/LoginPage.css');
  const css = readSrc('src/styles/global.css');
  assert.match(chat, /useMediaQuery\(COMPACT_CHAT_MEDIA\)/);
  assert.match(chat, /className="chat-session-toggle"/);
  assert.match(chat, /chat-panel-rail-open/);
  assert.match(chat, /className="chat-session-backdrop"/);
  assert.doesNotMatch(chat, /from 'antd'/);
  assert.doesNotMatch(readSrc('src/components/LoginPage.tsx'), /from 'antd'/);
  assert.match(loginCss, /@media \(max-width:\s*768px\)/);
  assert.match(loginCss, /@media \(max-width:\s*430px\)/);
  assert.match(css, /@media \(max-width:\s*767px\)/);
  assert.match(css, /\.chat-panel-rail-open \.session-rail/);
});

test('report and workbench tabs can scroll instead of stretching the page', () => {
  const css = readSrc('src/styles/global.css');
  const reports = readSrc('src/components/ReportWorkbench.tsx');
  assert.match(css, /\.memory-tabs/);
  assert.match(css, /-webkit-overflow-scrolling:\s*touch/);
  assert.match(css, /\.report-chart/);
  assert.match(css, /overflow-x:\s*auto/);
  assert.match(reports, /QueryTable/);
  assert.match(reports, /role="alert"/);
  assert.doesNotMatch(reports, /parseFloat\s*\(/);
});

test('M7-T07 does not touch prebuilt web-dist or server', () => {
  assert.equal(existsSync(join(webRoot, 'src/layout/useMediaQuery.ts')), true);
  assert.doesNotMatch(readSrc('src/App.tsx'), /web-dist/);
  assert.doesNotMatch(readSrc('src/ui/WorkbenchTable.tsx'), /web-dist/);
  assert.doesNotMatch(readSrc('src/layout/useMediaQuery.ts'), /web-dist/);
});
