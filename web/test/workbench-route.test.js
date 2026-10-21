import test from 'node:test';
import assert from 'node:assert/strict';

import { moduleFromHash } from '../src/security/routeGuard.ts';
import {
  hashForRoute,
  parseWorkbenchRoute,
  resolveWorkbenchRoute,
} from '../src/security/workbenchRoute.ts';

test('parseWorkbenchRoute keeps moduleFromHash compatible and captures tab/docNo', () => {
  assert.equal(moduleFromHash('#/purchase/order/PO-1'), 'purchase');
  assert.deepEqual(parseWorkbenchRoute('#/purchase/order/PO-202607-0001'), {
    module: 'purchase',
    tab: 'order',
    docNo: 'PO-202607-0001',
  });
  assert.equal(hashForRoute({
    module: 'sales',
    tab: 'invoice',
    docNo: 'SINV-202607-0001',
  }), '#/sales/invoice/SINV-202607-0001');
});

test('resolveWorkbenchRoute drops unauthorized document tabs fail-closed', () => {
  const requested = parseWorkbenchRoute('#/purchase/order/PO-1');
  const warehouseOnly = resolveWorkbenchRoute(requested, ['purchase:receipt']);
  assert.deepEqual(warehouseOnly, { module: 'purchase' });

  const withOrder = resolveWorkbenchRoute(requested, ['purchase:order']);
  assert.deepEqual(withOrder, {
    module: 'purchase',
    tab: 'order',
    docNo: 'PO-1',
  });

  const noPurchase = resolveWorkbenchRoute(requested, ['sales:order']);
  assert.equal(noPurchase.module, 'agent');
  assert.equal(noPurchase.tab, undefined);
  assert.equal(noPurchase.docNo, undefined);
});

test('report tabs fail-closed on their REST permission while login-scoped reports stay open', () => {
  assert.deepEqual(
    resolveWorkbenchRoute({ module: 'reports', tab: 'balance-sheet' }, []),
    { module: 'reports' },
  );
  assert.deepEqual(
    resolveWorkbenchRoute({ module: 'reports', tab: 'trial-balance' }, ['finance:voucher']),
    { module: 'reports', tab: 'trial-balance', docNo: undefined },
  );
  assert.deepEqual(
    resolveWorkbenchRoute({ module: 'reports', tab: 'inventory-balance' }, []),
    { module: 'reports', tab: 'inventory-balance', docNo: undefined },
  );
  assert.deepEqual(
    resolveWorkbenchRoute({ module: 'reports', tab: 'receivable-aging' }, ['sales:order']),
    { module: 'reports' },
  );
});
