package com.sjherp.infra.persistence.inventory;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.inventory.InventoryTransaction;
import com.sjherp.domain.inventory.InventoryTransactionRepository;
import com.sjherp.domain.inventory.InventoryTxnType;

/**
 * 库存流水仓储的 MySQL 实现（M3-T01b，拆解 §1.2；代码风格照 {@code JdbcCustomerRepository}）。
 *
 * <p>流水<b>只插入、不更新不删除</b>（纠错走反向流水）——本实现刻意只有 INSERT 与查询。
 * idempotency_key 撞数据库唯一键 uk_inventory_txn_idempotency 时由 Spring 异常翻译抛
 * {@link org.springframework.dao.DuplicateKeyException} 兜底（正常路径由
 * {@code InventoryService} 先查 {@link #findByIdempotencyKey} 走幂等重放）。
 *
 * <p>tenant_id / batch_id v1.0 恒 0（ADR-002 / Q-2），由本层落列，领域层不出现。
 * 时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcInventoryTransactionRepository implements InventoryTransactionRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, warehouse_id, product_id, txn_type, quantity, unit_cost, total_cost, "
                    + "balance_quantity_after, balance_amount_after, src_doc_type, src_doc_no, "
                    + "src_line_no, idempotency_key, operator, created_at FROM inventory_transaction ";

    private static final RowMapper<InventoryTransaction> ROW_MAPPER = (rs, rowNum) -> {
        // src_line_no 可空：getInt 对 NULL 返回 0，须经 wasNull 区分
        int srcLineNo = rs.getInt("src_line_no");
        return InventoryTransaction.restore(
                rs.getLong("id"),
                rs.getLong("warehouse_id"),
                rs.getLong("product_id"),
                InventoryTxnType.valueOf(rs.getString("txn_type")),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("total_cost"),
                rs.getBigDecimal("balance_quantity_after"),
                rs.getBigDecimal("balance_amount_after"),
                rs.getString("src_doc_type"),
                rs.getString("src_doc_no"),
                rs.wasNull() ? null : srcLineNo,
                rs.getString("idempotency_key"),
                rs.getString("operator"),
                fromDb(rs.getObject("created_at", LocalDateTime.class)));
    };

    private final JdbcTemplate jdbc;

    public JdbcInventoryTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(InventoryTransaction transaction) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inventory_transaction (warehouse_id, product_id, txn_type, quantity, "
                            + "unit_cost, total_cost, balance_quantity_after, balance_amount_after, "
                            + "src_doc_type, src_doc_no, src_line_no, idempotency_key, operator, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, transaction.getWarehouseId());
            ps.setLong(2, transaction.getProductId());
            ps.setString(3, transaction.getTxnType().name());
            ps.setBigDecimal(4, transaction.getQuantity());
            ps.setBigDecimal(5, transaction.getUnitCost());
            ps.setBigDecimal(6, transaction.getTotalCost());
            ps.setBigDecimal(7, transaction.getBalanceQuantityAfter());
            ps.setBigDecimal(8, transaction.getBalanceAmountAfter());
            ps.setString(9, transaction.getSrcDocType());
            ps.setString(10, transaction.getSrcDocNo());
            if (transaction.getSrcLineNo() == null) {
                ps.setNull(11, Types.INTEGER);
            } else {
                ps.setInt(11, transaction.getSrcLineNo());
            }
            ps.setString(12, transaction.getIdempotencyKey());
            ps.setString(13, transaction.getOperator());
            ps.setObject(14, toDb(transaction.getCreatedAt()));
            return ps;
        }, keyHolder);
        transaction.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryTransaction> findByIdempotencyKey(String idempotencyKey) {
        List<InventoryTransaction> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE tenant_id = 0 AND idempotency_key = ?",
                ROW_MAPPER, idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryTransaction> findLatestWithUnitCost(long warehouseId, long productId) {
        // 限定 unit_cost 非空（COST_ADJUST 流水单价为 NULL，无法为出库定价，拆解 §1.5）；
        // id 倒序即过账倒序（命中 idx_inventory_txn_dim 索引尾列）
        List<InventoryTransaction> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND unit_cost IS NOT NULL ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, warehouseId, productId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
