package com.sjherp.app.inventory;

import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.inventory.StockChecker;

/**
 * 库存占用检查端口的只读 SQL 实现（M3-T01c，补 M2 遗留 TODO：仓库/商品停用前的
 * 非零库存引用检查）。装配进 {@code WarehouseService} / {@code ProductService}
 * （见 WarehouseInfraConfig / CatalogInfraConfig）。
 *
 * <p>口径（见 {@link StockChecker}）：余额行 quantity 或 cost_amount 任一非 0 即
 * 视为存在库存占用——宁可拒绝，不可破坏模型。只读查询，不属于库存两表写入口。
 */
@Repository
public class JdbcStockChecker implements StockChecker {

    private final JdbcTemplate jdbc;

    public JdbcStockChecker(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean warehouseHasStock(long warehouseId) {
        return exists("SELECT EXISTS(SELECT 1 FROM inventory_balance"
                + " WHERE tenant_id = 0 AND warehouse_id = ?"
                + " AND (quantity <> 0 OR cost_amount <> 0))", warehouseId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean productHasStock(long productId) {
        return exists("SELECT EXISTS(SELECT 1 FROM inventory_balance"
                + " WHERE tenant_id = 0 AND product_id = ?"
                + " AND (quantity <> 0 OR cost_amount <> 0))", productId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean productHasTransactions(long productId) {
        return exists("SELECT EXISTS(SELECT 1 FROM inventory_transaction"
                + " WHERE tenant_id = 0 AND product_id = ?)", productId);
    }

    private boolean exists(String sql, long id) {
        Boolean result = jdbc.queryForObject(sql, Boolean.class, id);
        return Boolean.TRUE.equals(result);
    }
}
