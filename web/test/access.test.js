import test from 'node:test';
import assert from 'node:assert/strict';

import {
  canAccessModule,
  filterNavItems,
  getPermissionsForRoles,
} from '../src/security/access.ts';
import { MODULE_NAV_ITEMS } from '../src/types/navigation.ts';

test('permission grants expose only modules allowed by the signed-in roles', () => {
  const purchaserItems = filterNavItems(MODULE_NAV_ITEMS, ['PURCHASER']);
  assert.deepEqual(
    purchaserItems.map((item) => item.key),
    ['agent', 'purchase'],
  );
  assert.equal(canAccessModule('finance', ['PURCHASER']), false);
  assert.equal(canAccessModule('purchase', ['PURCHASER']), true);
});

test('multiple roles combine permissions without granting unknown roles', () => {
  const permissions = getPermissionsForRoles(['SALES', 'ACCOUNTANT']);
  assert.equal(permissions.has('sales:order'), true);
  assert.equal(permissions.has('finance:voucher'), true);
  assert.equal(permissions.has('memory:manage'), false);
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, ['UNKNOWN']).map((item) => item.key),
    ['agent'],
  );
});

test('administrators can access every registered navigation module', () => {
  assert.deepEqual(
    filterNavItems(MODULE_NAV_ITEMS, ['ADMIN']).map((item) => item.key),
    MODULE_NAV_ITEMS.map((item) => item.key),
  );
  assert.equal(canAccessModule('memory', ['ADMIN']), true);
});
