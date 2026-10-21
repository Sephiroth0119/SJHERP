-- M8-T05：库存流水来源单据查找索引。
-- 勾稽/红冲/COGS 比对都是相关子查询：
--   WHERE tenant_id = 0 AND src_doc_no = ? AND src_line_no = ?
-- 既有 idx_inventory_txn_dim (tenant_id, warehouse_id, product_id, id) 服务过账锁行与台账，
-- 不能覆盖按来源单号点查。百万行流水下缺本索引会退化成全表扫描（EXPLAIN type=ALL）。
-- 本迁移只加二级索引，不改列、不改写路径；库存仍只允许 InventoryService 写入。
ALTER TABLE inventory_transaction
    ADD KEY idx_inventory_txn_src (tenant_id, src_doc_no, src_line_no);
