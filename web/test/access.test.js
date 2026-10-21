import test from 'node:test';
import assert from 'node:assert/strict';

import {
  canAccessModule,
  filterNavItems,
} from '../src/security/access.ts';
import { MODULE_NAV_ITEMS } from '../src/types/navigation.ts';

test('permission grants expose only modules allowed by the signed-in permission set', () => {
  const purchaserItems = filterNavItems(MODULE_NAV_ITEMS, [
    'partner:create_supplier',
    'purchase:order',
    'purchase:receipt',
    'purchase:invoice',
  ]);
  assert.deepEqual(
    purchaserItems.map((item) => item.key),
    ['agent', 'purchase', 'reports', 'help'],
  );
  assert.equal(canAccessModule('finance', ['purchase:order']), false);
  assert.equal(canAccessModule('purchase', ['purchase:order']), true);
});

test('finance settlement permission unlocks purchase and sales payment pages', () => {
  assert.equal(canAccessModule('purchase', ['finance:settlement']), true);
  assert.equal(canAccessModule('sales', ['finance:settlement']), true);
  assert.equal(canAccessModule('purchase', ['sales:order']), false);
});

test('any inventory permission unlocks the inventory module for ledger query', () => {
  assert.equal(canAccessModule('inventory', ['inventory:count']), true);
  assert.equal(canAccessModule('inventory', ['inventory:transfer']), true);
  assert.equal(canAccessModule('inventory', ['inventory:adjust']), true);
  assert.equal(canAccessModule('inventory', ['purchase:order']), false);
});

test('unknown or unrelated permission codes do not unlock navigation', () => {
  const permissions = ['sales:order', 'finance:voucher', 'permission:from-a-new-server'];
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, permissions).map((item) => item.key),
    ['agent', 'sales', 'finance', 'reports', 'help'],
  );
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, ['UNKNOWN']).map((item) => item.key),
    ['agent', 'reports', 'help'],
  );
});

test('reports module is visible to every signed-in user because inventory reports are login-scoped', () => {
  assert.equal(canAccessModule('reports', []), true);
  assert.equal(canAccessModule('reports', ['UNKNOWN']), true);
  assert.equal(canAccessModule('finance', []), false);
});

test('help module is visible to every signed-in user', () => {
  assert.equal(canAccessModule('help', []), true);
  assert.equal(canAccessModule('help', ['UNKNOWN']), true);
});

test('the frontend does not infer elevated access from a role label', () => {
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, ['ADMIN']).map((item) => item.key),
    ['agent', 'reports', 'help'],
  );
  assert.equal(canAccessModule('memory', ['ADMIN']), false);
});
