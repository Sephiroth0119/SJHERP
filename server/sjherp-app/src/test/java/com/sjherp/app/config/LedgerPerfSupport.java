package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M8-T05 压测夹具：合成流水只用于 EXPLAIN / 大表体积，仓库 id 落在保留区间，
 * 不与领域过账用例的发号器碰撞。合成行没有对应余额，禁止对它们走 InventoryService。
 */
final class LedgerPerfSupport {

    /** 合成流水占用的仓库/商品，避免污染业务过账维度 */
    static final long SEED_WAREHOUSE_ID = 2_000_000_001L;
    static final long SEED_PRODUCT_ID = 2_000_000_001L;

    static final int SMOKE_ROWS = 4_096;
    static final int FULL_ROWS = 1_048_576;

    static final long P95_LIMIT_MS = 10_000L;

    private LedgerPerfSupport() {
    }

    static long seedSyntheticTransactions(JdbcTemplate jdbc, int targetRows) {
        Long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ?",
                Long.class, SEED_WAREHOUSE_ID);
        long count = existing == null ? 0 : existing;
        if (count == 0) {
            jdbc.update(
                    "INSERT INTO inventory_transaction ("
                            + "warehouse_id, product_id, txn_type, quantity, unit_cost, total_cost, "
                            + "balance_quantity_after, balance_amount_after, src_doc_type, src_doc_no, "
                            + "src_line_no, idempotency_key, operator, created_at) "
                            + "VALUES (?, ?, 'PURCHASE_IN', 1.000000, 1.000000, 1.00, 1.000000, 1.00, "
                            + "'PERF', 'PERF-0', 1, 'PERFSEED:0', 'perf-seed', UTC_TIMESTAMP(6))",
                    SEED_WAREHOUSE_ID, SEED_PRODUCT_ID);
            count = 1;
        }
        while (count < targetRows) {
            jdbc.update(
                    "INSERT INTO inventory_transaction ("
                            + "warehouse_id, product_id, txn_type, quantity, unit_cost, total_cost, "
                            + "balance_quantity_after, balance_amount_after, src_doc_type, src_doc_no, "
                            + "src_line_no, idempotency_key, operator, created_at) "
                            + "SELECT warehouse_id, product_id, txn_type, quantity, unit_cost, total_cost, "
                            + "balance_quantity_after, balance_amount_after, src_doc_type, "
                            + "CONCAT('PERF-', id), 1, CONCAT('PERFSEED:', id, '-', UUID()), "
                            + "operator, created_at "
                            + "FROM inventory_transaction WHERE warehouse_id = ?",
                    SEED_WAREHOUSE_ID);
            Long next = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ?",
                    Long.class, SEED_WAREHOUSE_ID);
            long grown = next == null ? count : next;
            if (grown <= count) {
                throw new IllegalStateException("合成流水倍增未增长，当前 " + count);
            }
            count = grown;
        }
        return count;
    }

    static void assertUsesIndex(JdbcTemplate jdbc, String sql, String expectedKey, Object... args) {
        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + sql, args);
        assertThat(plan).as("EXPLAIN 应返回计划：%s", sql).isNotEmpty();
        Map<String, Object> row = plan.get(0);
        String key = Objects.toString(row.get("key"), "");
        String possible = Objects.toString(row.get("possible_keys"), "");
        String type = Objects.toString(row.get("type"), "");
        assertThat(key + "," + possible)
                .as("热查询应能命中索引 %s（type=%s key=%s possible=%s）SQL=%s",
                        expectedKey, type, key, possible, sql)
                .contains(expectedKey);
        if ("ALL".equals(type) && (key.isBlank() || "null".equalsIgnoreCase(key))) {
            throw new AssertionError("热查询全表扫描且未选索引：" + sql + " plan=" + row);
        }
    }

    static long percentile(List<Long> sortedMillis, double p) {
        if (sortedMillis.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(sortedMillis.size() * p) - 1;
        return sortedMillis.get(Math.max(0, Math.min(index, sortedMillis.size() - 1)));
    }

    static void assertLedgerIdentity(JdbcTemplate jdbc, long warehouseId, long productId) {
        Map<String, Object> sums = jdbc.queryForMap(
                "SELECT SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum "
                        + "FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? "
                        + "AND product_id = ?",
                warehouseId, productId);
        Map<String, Object> balance = jdbc.queryForMap(
                "SELECT quantity, cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        assertThat((BigDecimal) sums.get("qty_sum"))
                .as("Σ流水数量 = 余额数量（仓库 %d 商品 %d）", warehouseId, productId)
                .isEqualByComparingTo((BigDecimal) balance.get("quantity"));
        assertThat((BigDecimal) sums.get("cost_sum"))
                .as("Σ流水金额 = 余额金额（仓库 %d 商品 %d）", warehouseId, productId)
                .isEqualByComparingTo((BigDecimal) balance.get("cost_amount"));
    }
}
