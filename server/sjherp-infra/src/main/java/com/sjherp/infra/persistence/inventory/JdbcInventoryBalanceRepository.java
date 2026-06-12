package com.sjherp.infra.persistence.inventory;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.inventory.InventoryBalance;
import com.sjherp.domain.inventory.InventoryBalanceRepository;

/**
 * 库存余额仓储的 MySQL 实现（M3-T01b，拆解 §1.4；代码风格照 {@code JdbcCustomerRepository}）。
 *
 * <p><b>lockForUpdate 契约实现</b>：{@code SELECT ... FOR UPDATE} 按唯一键
 * (tenant_id, warehouse_id, product_id, batch_id) 定位加行锁；行不存在时 INSERT
 * 初始零行（数量 0.000000 / 金额 0.00）再持锁返回，并发撞唯一键则捕获
 * {@link DuplicateKeyException} 退回锁行路径（同 {@code JdbcSequenceProvider} 模式）。
 * 锁随<b>外层业务事务</b>提交/回滚释放——本方法标注 {@link Propagation#MANDATORY}：
 * 无外层事务时直接拒绝（FOR UPDATE 不在事务内毫无意义，fail-fast 防误用）。
 *
 * <p>锁顺序约定（防死锁）由调用方 {@code InventoryService} 保证：多行按
 * (warehouse_id, product_id) 升序依次调用，本实现不重排。
 *
 * <p>tenant_id / batch_id v1.0 恒 0（ADR-002 / Q-2），由本层落列，领域层不出现。
 * 时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcInventoryBalanceRepository implements InventoryBalanceRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, warehouse_id, product_id, quantity, cost_amount, updated_by, updated_at "
                    + "FROM inventory_balance ";

    /** v1.0 维度恒定条件（tenant_id 恒 0、batch_id 恒 0，命中唯一键最左前缀） */
    private static final String WHERE_DIMENSION =
            "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0 ";

    private static final RowMapper<InventoryBalance> ROW_MAPPER = (rs, rowNum) -> InventoryBalance.restore(
            rs.getLong("id"),
            rs.getLong("warehouse_id"),
            rs.getLong("product_id"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("cost_amount"),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcInventoryBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InventoryBalance lockForUpdate(long warehouseId, long productId, String operator) {
        Objects.requireNonNull(operator, "operator 不能为空");

        InventoryBalance locked = selectForUpdate(warehouseId, productId);
        if (locked != null) {
            return locked;
        }
        // 行不存在：INSERT 初始零行（插入行由本事务持排他锁，等效已锁定）；
        // 并发撞唯一键则退回锁行路径（FOR UPDATE 等待对方事务结束后读到该行）
        try {
            InventoryBalance zero = InventoryBalance.openZero(warehouseId, productId, operator);
            insert(zero);
            return zero;
        } catch (DuplicateKeyException e) {
            InventoryBalance existing = selectForUpdate(warehouseId, productId);
            if (existing == null) {
                // 理论不可达：撞唯一键说明行已存在（对方事务回滚的极端窗口由上层重试兜底）
                throw new IllegalStateException("inventory_balance 零行插入冲突后仍不可见: 仓库["
                        + warehouseId + "] 商品[" + productId + "]", e);
            }
            return existing;
        }
    }

    @Override
    public void save(InventoryBalance balance) {
        if (balance.getId() == null) {
            insert(balance);
        } else {
            update(balance);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryBalance> find(long warehouseId, long productId) {
        List<InventoryBalance> rows = jdbc.query(SELECT_COLUMNS + WHERE_DIMENSION,
                ROW_MAPPER, warehouseId, productId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 行锁读取（行不存在返回 null）；锁随外层业务事务结束释放 */
    private InventoryBalance selectForUpdate(long warehouseId, long productId) {
        List<InventoryBalance> rows = jdbc.query(
                SELECT_COLUMNS + WHERE_DIMENSION + "FOR UPDATE", ROW_MAPPER, warehouseId, productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insert(InventoryBalance balance) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inventory_balance (warehouse_id, product_id, quantity, cost_amount, "
                            + "updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, balance.getWarehouseId());
            ps.setLong(2, balance.getProductId());
            ps.setBigDecimal(3, balance.getQuantity());
            ps.setBigDecimal(4, balance.getCostAmount());
            ps.setString(5, balance.getUpdatedBy());
            ps.setObject(6, toDb(balance.getUpdatedAt()));
            return ps;
        }, keyHolder);
        balance.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    private void update(InventoryBalance balance) {
        // 维度列 (warehouse_id, product_id) 落库后不可变，更新只触碰余额真源两列与审计列
        jdbc.update("UPDATE inventory_balance SET quantity = ?, cost_amount = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                balance.getQuantity(), balance.getCostAmount(), balance.getUpdatedBy(),
                toDb(balance.getUpdatedAt()), balance.getId());
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
