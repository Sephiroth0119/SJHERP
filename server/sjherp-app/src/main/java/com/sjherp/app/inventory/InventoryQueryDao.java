package com.sjherp.app.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;

/**
 * 库存查询 DAO（M3-T01c，<b>只读</b>）：余额列表（联查商品/仓库名，报表口径）与
 * 流水明细分页。CLAUDE.md「报表只读查询除外」——本类只有 SELECT，库存两表的写入
 * 仍唯一经 {@code InventoryService}（路线图 §13 铁律）。
 *
 * <p>余额关键字过滤联查 product 的名称/编码（用户记不住商品 id）；
 * 余额行按 (warehouse_id, product_id) 升序稳定输出，流水按 id 倒序（最近优先）。
 * tenant_id 恒 0（ADR-002），时间列 DATETIME(6) 按 UTC 读（约定同 infra 仓储）。
 */
@Repository
public class InventoryQueryDao {

    /** 每页条数上限（防止一次拉全表，口径同各档案查询） */
    public static final int MAX_SIZE = 200;

    /** 余额行视图（真源两列 quantity/costAmount；派生加权单价由 DTO 层现算，不在 SQL 里除——口径统一） */
    public record BalanceRow(long warehouseId, String warehouseCode, String warehouseName,
                             long productId, String productCode, String productName,
                             BigDecimal quantity, BigDecimal costAmount) {
    }

    /** 流水行视图（unit_cost 成本调整为 null；src_line_no 可空） */
    public record TransactionRow(long id, String txnType, BigDecimal quantity, BigDecimal unitCost,
                                 BigDecimal totalCost, BigDecimal balanceQuantityAfter,
                                 BigDecimal balanceAmountAfter, String srcDocType, String srcDocNo,
                                 Integer srcLineNo, String operator, Instant createdAt) {
    }

    private static final RowMapper<BalanceRow> BALANCE_MAPPER = (rs, rowNum) -> new BalanceRow(
            rs.getLong("warehouse_id"),
            rs.getString("warehouse_code"),
            rs.getString("warehouse_name"),
            rs.getLong("product_id"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("cost_amount"));

    private static final RowMapper<TransactionRow> TXN_MAPPER = (rs, rowNum) -> {
        int srcLineNo = rs.getInt("src_line_no");
        boolean srcLineNoNull = rs.wasNull();
        return new TransactionRow(
                rs.getLong("id"),
                rs.getString("txn_type"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("total_cost"),
                rs.getBigDecimal("balance_quantity_after"),
                rs.getBigDecimal("balance_amount_after"),
                rs.getString("src_doc_type"),
                rs.getString("src_doc_no"),
                srcLineNoNull ? null : srcLineNo,
                rs.getString("operator"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    };

    private final JdbcTemplate jdbc;

    public InventoryQueryDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    /**
     * 余额分页：warehouseId / productId / keyword（模糊匹配商品名称或编码）均可选。
     * 不隐藏零余额行（出空清零后的 (0, 0.00) 行保留——用户需要知道"曾有现已出空"）。
     */
    @Transactional(readOnly = true)
    public PageResult<BalanceRow> balances(Long warehouseId, Long productId, String keyword,
                                           int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        StringBuilder where = new StringBuilder(" WHERE b.tenant_id = 0");
        List<Object> args = new ArrayList<>();
        if (warehouseId != null) {
            where.append(" AND b.warehouse_id = ?");
            args.add(warehouseId);
        }
        if (productId != null) {
            where.append(" AND b.product_id = ?");
            args.add(productId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (p.name LIKE ? OR p.code LIKE ?)");
            String like = "%" + keyword.strip() + "%";
            args.add(like);
            args.add(like);
        }

        String from = "FROM inventory_balance b"
                + " JOIN warehouse w ON w.id = b.warehouse_id"
                + " JOIN product p ON p.id = b.product_id" + where;

        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());

        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<BalanceRow> rows = jdbc.query(
                "SELECT b.warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name, "
                        + "b.product_id, p.code AS product_code, p.name AS product_name, "
                        + "b.quantity, b.cost_amount " + from
                        + " ORDER BY b.warehouse_id, b.product_id LIMIT ? OFFSET ?",
                BALANCE_MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** 流水分页：仓库 × 商品必填（拆解 §1.2 索引口径），按 id 倒序（最近过账在前） */
    @Transactional(readOnly = true)
    public PageResult<TransactionRow> transactions(long warehouseId, long productId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction"
                        + " WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                Long.class, warehouseId, productId);

        List<TransactionRow> rows = jdbc.query(
                "SELECT id, txn_type, quantity, unit_cost, total_cost, balance_quantity_after, "
                        + "balance_amount_after, src_doc_type, src_doc_no, src_line_no, operator, created_at "
                        + "FROM inventory_transaction"
                        + " WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?"
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                TXN_MAPPER, warehouseId, productId, safeSize, (safePage - 1) * safeSize);
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }
}
