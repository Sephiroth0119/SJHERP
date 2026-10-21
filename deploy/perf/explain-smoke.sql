-- M8-T05 只读 EXPLAIN 冒烟（不对业务表 INSERT）。
-- 空表/小表也要能看到 possible_keys / key，证明过账与勾稽不会被迫全表扫描。
-- 由 ./deploy/perf.sh --smoke 在运行中的 compose MySQL 上执行。

SHOW INDEX FROM inventory_balance;
SHOW INDEX FROM inventory_transaction;
SHOW INDEX FROM voucher;
SHOW INDEX FROM voucher_line;

EXPLAIN SELECT id, warehouse_id, product_id, quantity, cost_amount
  FROM inventory_balance
  WHERE tenant_id = 0 AND warehouse_id = 1 AND product_id = 1 AND batch_id = 0
  FOR UPDATE;

EXPLAIN SELECT id, unit_cost FROM inventory_transaction
  WHERE tenant_id = 0 AND warehouse_id = 1 AND product_id = 1
    AND unit_cost IS NOT NULL
  ORDER BY id DESC LIMIT 1;

EXPLAIN SELECT id FROM inventory_transaction
  WHERE tenant_id = 0 AND idempotency_key = 'PERFSEED:missing';

EXPLAIN SELECT id, total_cost FROM inventory_transaction
  WHERE tenant_id = 0 AND src_doc_no = 'SD-MISSING' AND src_line_no = 1;

EXPLAIN SELECT id, quantity, total_cost FROM inventory_transaction
  WHERE tenant_id = 0 AND warehouse_id = 1 AND product_id = 1
  ORDER BY id DESC LIMIT 20;

EXPLAIN SELECT vl.account_code,
         COALESCE(SUM(vl.debit), 0) AS total_debit,
         COALESCE(SUM(vl.credit), 0) AS total_credit
  FROM voucher_line vl
  JOIN voucher v ON vl.voucher_id = v.id
  WHERE v.tenant_id = 0 AND v.period = '2026-01'
    AND v.status IN ('APPROVED', 'REVERSED')
  GROUP BY vl.account_code
  ORDER BY vl.account_code;
