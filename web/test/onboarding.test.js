import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  canRunOnboardingStep,
  emptyOnboardingContext,
  onboardingStorageKey,
  parseChoiceId,
  readOnboardingStatus,
  shouldAutoOpenOnboarding,
  visibleOnboardingSteps,
  writeOnboardingStatus,
} from '../src/onboarding/onboarding.ts';
import { GETTING_STARTED_STEPS, HELP_TOPICS } from '../src/onboarding/helpContent.ts';
import {
  applyArchiveStep,
  applyFirstOrderStep,
  applyOpeningStep,
} from '../src/onboarding/runOnboarding.ts';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = join(webRoot, '..');
const readSrc = (relative) => readFileSync(join(webRoot, relative), 'utf8');
const unicodeEscape = /\\u[0-9a-fA-F]{4}/;

class MemoryStorage {
  #values = new Map();

  getItem(key) {
    return this.#values.get(key) ?? null;
  }

  setItem(key, value) {
    this.#values.set(key, String(value));
  }

  removeItem(key) {
    this.#values.delete(key);
  }
}

const adminPermissions = [
  'catalog:write',
  'catalog:create_product',
  'warehouse:create_warehouse',
  'partner:create_supplier',
  'partner:create_customer',
  'inventory:adjust',
  'purchase:order',
];

function jsonResponse(body) {
  return { ok: true, status: 200, json: async () => body };
}

test('onboarding status is stored per username and defaults to pending', () => {
  const previous = globalThis.localStorage;
  globalThis.localStorage = new MemoryStorage();
  try {
    assert.equal(onboardingStorageKey('admin'), 'sjherp.onboarding.status.admin');
    assert.equal(readOnboardingStatus('admin'), 'pending');
    assert.equal(shouldAutoOpenOnboarding('admin'), true);
    writeOnboardingStatus('admin', 'dismissed');
    writeOnboardingStatus('boss', 'completed');
    assert.equal(readOnboardingStatus('admin'), 'dismissed');
    assert.equal(shouldAutoOpenOnboarding('admin'), false);
    assert.equal(readOnboardingStatus('boss'), 'completed');
    assert.equal(shouldAutoOpenOnboarding(''), false);
  } finally {
    globalThis.localStorage = previous;
  }
});

test('wizard write steps follow permissions fail-closed', () => {
  assert.deepEqual(visibleOnboardingSteps([]), ['welcome', 'done']);
  assert.deepEqual(visibleOnboardingSteps(['inventory:adjust']), ['welcome', 'opening', 'done']);
  assert.deepEqual(visibleOnboardingSteps(['purchase:order']), ['welcome', 'first-order', 'done']);
  assert.deepEqual(
    visibleOnboardingSteps(adminPermissions),
    ['welcome', 'archives', 'opening', 'first-order', 'done'],
  );
  assert.equal(canRunOnboardingStep('archives', ['sales:order']), false);
  assert.equal(canRunOnboardingStep('opening', ['inventory:count']), false);
  assert.equal(parseChoiceId('new'), 'new');
  assert.equal(parseChoiceId('12'), 12);
  assert.equal(parseChoiceId('0'), null);
});

test('archive step posts real create APIs and refuses empty fake names', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
  globalThis.fetch = async (input, init = {}) => {
    calls.push({ url: String(input), method: init.method ?? 'GET', body: init.body });
    if (String(input).endsWith('/api/catalog/units')) {
      return jsonResponse({ id: 9, name: '个', precision: 0 });
    }
    if (String(input).endsWith('/api/warehouse/warehouses')) {
      return jsonResponse({ id: 3, code: 'WH-1', name: '成品仓' });
    }
    if (String(input).endsWith('/api/catalog/products')) {
      return jsonResponse({
        id: 8,
        code: 'P-1',
        name: '螺丝',
        inventoryCategory: 'MERCHANDISE',
        baseUnitId: 9,
        unitConversions: [],
      });
    }
    if (String(input).endsWith('/api/partner/suppliers')) {
      return jsonResponse({ id: 4, code: 'SUP-1', name: '华东金属', settlementMethod: 'MONTHLY' });
    }
    throw new Error(`unexpected ${input}`);
  };
  try {
    await assert.rejects(
      () => applyArchiveStep({
        unitChoice: 'new',
        unitName: '',
        productChoice: 'new',
        productName: '螺丝',
        inventoryCategory: 'MERCHANDISE',
        warehouseChoice: 'new',
        warehouseName: '成品仓',
        supplierChoice: 'new',
        supplierName: '华东金属',
        supplierSettlement: 'MONTHLY',
        customerChoice: 'new',
        customerName: '',
        customerSettlement: 'MONTHLY',
      }, adminPermissions, emptyOnboardingContext()),
      /单位名称/,
    );
    assert.equal(calls.length, 0);

    const result = await applyArchiveStep({
      unitChoice: 'new',
      unitName: '个',
      productChoice: 'new',
      productName: '螺丝',
      inventoryCategory: 'MERCHANDISE',
      warehouseChoice: 'new',
      warehouseName: '成品仓',
      supplierChoice: 'new',
      supplierName: '华东金属',
      supplierSettlement: 'MONTHLY',
      customerChoice: 'new',
      customerName: '',
      customerSettlement: 'MONTHLY',
    }, adminPermissions, emptyOnboardingContext());
    assert.deepEqual(result, {
      unitId: 9,
      productId: 8,
      warehouseId: 3,
      supplierId: 4,
      customerId: null,
    });
    assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [
      'POST /api/catalog/units',
      'POST /api/warehouse/warehouses',
      'POST /api/catalog/products',
      'POST /api/partner/suppliers',
    ]);
    assert.equal(JSON.parse(calls[2].body).name, '螺丝');
    assert.equal(JSON.parse(calls[2].body).baseUnitId, 9);
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('opening and first order reuse existing inventory and purchase APIs', async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.localStorage;
  const calls = [];
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
  globalThis.fetch = async (input, init = {}) => {
    calls.push({ url: String(input), method: init.method ?? 'GET', body: init.body });
    if (String(input).endsWith('/api/inventory/adjustments')) {
      return jsonResponse({
        transactionId: 11,
        warehouseId: 3,
        productId: 8,
        txnType: 'OPENING',
        quantity: '10',
        unitCost: '1.50',
        totalCost: '15.00',
        balanceQuantityAfter: '10',
        balanceAmountAfter: '15.00',
        srcDocNo: 'OP-202610-0001',
      });
    }
    return jsonResponse({
      docNo: 'PO-202610-0001',
      supplierId: 4,
      orderDate: '2026-10-21',
      status: 'DRAFT',
      totalAmount: '15.00',
      lines: [{ lineNo: 1, productId: 8, quantity: '10', unitPrice: '1.50', amount: '15.00', receivedQty: '0', outstandingQty: '10' }],
    });
  };
  try {
    const context = {
      unitId: 9,
      productId: 8,
      warehouseId: 3,
      supplierId: 4,
      customerId: null,
    };
    const opening = await applyOpeningStep({ quantity: '10', unitCost: '1.50' }, adminPermissions, context);
    const order = await applyFirstOrderStep(
      { orderDate: '2026-10-21', quantity: '10', unitPrice: '1.50' },
      adminPermissions,
      context,
    );
    assert.equal(opening.srcDocNo, 'OP-202610-0001');
    assert.equal(order.docNo, 'PO-202610-0001');
    assert.equal(JSON.parse(calls[0].body).type, 'OPENING');
    assert.equal(JSON.parse(calls[1].body).supplierId, 4);
    await assert.rejects(
      () => applyOpeningStep({ quantity: '10', unitCost: '1.50' }, ['purchase:order'], context),
      /inventory:adjust/,
    );
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.localStorage = previousStorage;
  }
});

test('app mounts the first-login wizard before the main shell and never seeds demo SKUs', () => {
  const app = readSrc('src/App.tsx');
  const wizard = readSrc('src/components/OnboardingWizard.tsx');
  const help = readSrc('src/components/HelpWorkbench.tsx');
  const run = readSrc('src/onboarding/runOnboarding.ts');
  const earlyReturnIndex = app.indexOf('  if (!user) {');
  const wizardStateIndex = app.indexOf('const [forceWizard, setForceWizard]');
  assert.ok(wizardStateIndex >= 0 && wizardStateIndex < earlyReturnIndex);
  assert.match(app, /<OnboardingWizard/);
  assert.match(app, /guardedModule === 'help'/);
  assert.match(app, /新手引导/);
  assert.match(wizard, /建档案 → 期初 → 第一张单/);
  assert.match(wizard, /applyArchiveStep/);
  assert.match(wizard, /applyOpeningStep/);
  assert.match(wizard, /applyFirstOrderStep/);
  assert.match(wizard, /createInFlightGuard/);
  assert.doesNotMatch(wizard, /测试商品|demo-sku|fake product/i);
  assert.doesNotMatch(run, /测试商品|DemoSupplier|seed/i);
  assert.match(help, /打开新手引导/);
  assert.doesNotMatch(`${app}\n${wizard}\n${help}`, unicodeEscape);
});

test('in-app help and repo manuals cover getting started plus each module', () => {
  assert.equal(GETTING_STARTED_STEPS.length, 3);
  assert.match(GETTING_STARTED_STEPS[0], /建档案/);
  assert.match(GETTING_STARTED_STEPS[1], /期初/);
  assert.match(GETTING_STARTED_STEPS[2], /采购订单/);
  const titles = HELP_TOPICS.map((topic) => topic.title);
  for (const title of ['基础档案', '采购', '销售', '库存', '生产', '财务', '报表', 'Agent 聊天']) {
    assert.equal(titles.includes(title), true, title);
  }

  const manuals = [
    'docs/用户-上手指南.md',
    'docs/用户-操作手册-档案.md',
    'docs/用户-操作手册-采购.md',
    'docs/用户-操作手册-销售.md',
    'docs/用户-操作手册-库存.md',
    'docs/用户-操作手册-生产.md',
    'docs/用户-操作手册-财务.md',
    'docs/用户-操作手册-Agent聊天.md',
    'docs/测试-M8-T06用户文档与引导.md',
  ];
  for (const relative of manuals) {
    const path = join(repoRoot, relative);
    assert.equal(existsSync(path), true, relative);
    const text = readFileSync(path, 'utf8');
    assert.match(text, /^# /m);
    assert.doesNotMatch(text, unicodeEscape);
  }
  assert.match(readFileSync(join(repoRoot, 'docs/用户-上手指南.md'), 'utf8'), /建档案[\s\S]*期初[\s\S]*第一张单/);
});
