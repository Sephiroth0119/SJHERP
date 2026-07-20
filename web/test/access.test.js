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
    ['agent', 'purchase'],
  );
  assert.equal(canAccessModule('finance', ['purchase:order']), false);
  assert.equal(canAccessModule('purchase', ['purchase:order']), true);
});

test('unknown or unrelated permission codes do not unlock navigation', () => {
  const permissions = ['sales:order', 'finance:voucher', 'permission:from-a-new-server'];
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, permissions).map((item) => item.key),
    ['agent', 'sales', 'finance'],
  );
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, ['UNKNOWN']).map((item) => item.key),
    ['agent'],
  );
});

test('the frontend does not infer elevated access from a role label', () => {
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, ['ADMIN']).map((item) => item.key),
    ['agent'],
  );
  assert.equal(canAccessModule('memory', ['ADMIN']), false);
});
