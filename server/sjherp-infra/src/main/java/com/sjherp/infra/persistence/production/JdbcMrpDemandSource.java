package com.sjherp.infra.persistence.production;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.production.MrpDemandSource;

/**
 * 销售订单实时剩余需求聚合（M5-T02，{@link MrpDemandSource} MySQL 实现）。
 *
 * <p>统计所有 APPROVED / EXECUTING 状态销售订单的未交货剩余量，按商品 id 聚合。
 * {@code sales_order_line.quantity} 与 {@code delivered_qty} 同为基本单位，差值即净剩余需求。
 * 仅纳入剩余量 > 0 的行（已全量发货行不参与 MRP 需求计算）。
 *
 * <p>此类为只读查询，不参与任何写事务。
 */
@Transactional(readOnly = true)
public class JdbcMrpDemandSource implements MrpDemandSource {

    /**
     * 剩余需求汇总 SQL：
     * <ul>
     *   <li>JOIN sales_order 过滤状态（APPROVED / EXECUTING）</li>
     *   <li>WHERE 子句额外排除剩余量 ≤ 0 的行（冗余防御，GROUP BY 后结果恒正）</li>
     *   <li>COALESCE 防 delivered_qty 为 NULL 时运算出 NULL</li>
     * </ul>
     */
    private static final String SQL =
            "SELECT sol.product_id, "
            + "       SUM(sol.quantity - COALESCE(sol.delivered_qty, 0)) AS remaining "
            + "FROM sales_order_line sol "
            + "JOIN sales_order so ON so.id = sol.sales_order_id "
            + "WHERE so.status IN ('APPROVED', 'EXECUTING') "
            + "  AND sol.quantity > COALESCE(sol.delivered_qty, 0) "
            + "GROUP BY sol.product_id";

    private final JdbcTemplate jdbc;

    public JdbcMrpDemandSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 返回各商品当前未交货的销售订单需求量（基本单位）。
     * 结果 map 不包含已全量发货的商品（余量 ≤ 0 的条目已被 WHERE 过滤）。
     */
    @Override
    public Map<Long, BigDecimal> openSalesOrderDemand() {
        List<Map<String, Object>> rows = jdbc.queryForList(SQL);
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long productId = ((Number) row.get("product_id")).longValue();
            BigDecimal remaining = (BigDecimal) row.get("remaining");
            // 双重防御：SUM 结果理论上恒 > 0（WHERE 已过滤），此处再次过滤 NULL 与 ≤ 0
            if (remaining != null && remaining.signum() > 0) {
                result.put(productId, remaining);
            }
        }
        return result;
    }
}
