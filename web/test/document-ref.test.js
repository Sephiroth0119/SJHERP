import test from 'node:test';
import assert from 'node:assert/strict';

import {
  hashForDocument,
  parseDocumentRef,
  visibleDocumentRefs,
} from '../src/security/documentRef.ts';

test('parseDocumentRef accepts registered types and rejects unknown or empty values', () => {
  const parsed = parseDocumentRef({
    type: 'PURCHASE_ORDER',
    docNo: 'PO-202607-0001',
    title: '采购订单',
  });
  assert.equal(parsed?.type, 'PURCHASE_ORDER');
  assert.equal(parsed?.docNo, 'PO-202607-0001');
  assert.equal(parsed?.permission, 'purchase:order');
  assert.equal(hashForDocument(parsed), '#/purchase/order/PO-202607-0001');

  assert.equal(parseDocumentRef({ type: 'UNKNOWN_DOC', docNo: 'X-1' }), null);
  assert.equal(parseDocumentRef({ type: 'PURCHASE_ORDER', docNo: '  ' }), null);
  assert.equal(parseDocumentRef(null), null);
});

test('visibleDocumentRefs is fail-closed: no permission hides the card and does not invent data', () => {
  const refs = [
    { type: 'PURCHASE_ORDER', docNo: 'PO-1' },
    { type: 'SALES_ORDER', docNo: 'SO-1' },
    { type: 'NOT_A_TYPE', docNo: 'XX-1' },
    { type: 'PURCHASE_ORDER', docNo: 'PO-1' },
  ];
  const visible = visibleDocumentRefs(refs, ['purchase:order']);
  assert.deepEqual(visible.map((item) => `${item.type}:${item.docNo}`), ['PURCHASE_ORDER:PO-1']);
  assert.equal(visibleDocumentRefs(refs, []).length, 0);
  assert.equal(visibleDocumentRefs(undefined, ['purchase:order']).length, 0);
});

test('purchase receipt and production report share PR- prefix but different types', () => {
  const receipt = parseDocumentRef({ type: 'PURCHASE_RECEIPT', docNo: 'PR-202607-0001' });
  const report = parseDocumentRef({ type: 'PRODUCTION_REPORT', docNo: 'PR-202607-0001' });
  assert.equal(hashForDocument(receipt), '#/purchase/receipt/PR-202607-0001');
  assert.equal(hashForDocument(report), '#/production/report/PR-202607-0001');
  assert.equal(
    visibleDocumentRefs([receipt, report], ['purchase:receipt']).length,
    1,
  );
});
