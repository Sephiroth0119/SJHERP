package com.sjherp.infra.persistence.production;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.Routing;
import com.sjherp.domain.production.RoutingOperation;
import com.sjherp.domain.production.RoutingQuery;
import com.sjherp.domain.production.RoutingRepository;

/**
 * 工艺路线仓储的 MySQL 实现。
 *
 * <p>聚合整体读写：save 在同一事务内持久化头与工序行（行整体替换——先删后插，
 * 值对象无独立生命周期）；find* 方法批量回带工序行（一次 IN 查询，避免 N+1）。
 * 时间列 DATETIME(6) 一律按 UTC 读写，与全库约定一致。
 * workCenter / costRate 在数据库中可为 NULL，读写时显式处理空值。
 */
@Transactional
public class JdbcRoutingRepository implements RoutingRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, product_id, version, status, remark, "
                    + "created_by, created_at, updated_by, updated_at FROM routing ";

    /** 工艺路线头中间载体（工序行单独查询后再 restore 成聚合） */
    private record RoutingRow(long id, long productId, int version, ArchiveStatus status, String remark,
                              String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    private static final RowMapper<RoutingRow> ROW_MAPPER = (rs, rowNum) -> new RoutingRow(
            rs.getLong("id"),
            rs.getLong("product_id"),
            rs.getInt("version"),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("remark"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static final RowMapper<RoutingOperation> OPERATION_ROW_MAPPER = (rs, rowNum) ->
            new RoutingOperation(
                    rs.getInt("sequence_no"),
                    rs.getString("operation_name"),
                    rs.getBigDecimal("standard_hours"),
                    rs.getObject("work_center", String.class),          // 可为 NULL
                    rs.getObject("cost_rate", BigDecimal.class));        // 可为 NULL

    private final JdbcTemplate jdbc;

    public JdbcRoutingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Routing routing) {
        if (routing.getId() == null) {
            insertRouting(routing);
        } else {
            updateRouting(routing);
            // 工序行整体替换：先清空再重插（值对象无独立生命周期）
            jdbc.update("DELETE FROM routing_operation WHERE routing_id = ?", routing.getId());
        }
        insertOperations(routing);
    }

    private void insertRouting(Routing routing) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO routing (product_id, version, status, remark, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, routing.getProductId());
            ps.setInt(2, routing.getVersion());
            ps.setString(3, routing.getStatus().name());
            ps.setString(4, routing.getRemark());
            ps.setString(5, routing.getCreatedBy());
            ps.setObject(6, toDb(routing.getCreatedAt()));
            ps.setString(7, routing.getUpdatedBy());
            ps.setObject(8, toDb(routing.getUpdatedAt()));
            return ps;
        }, keyHolder);
        routing.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得 routing 自增主键").longValue());
    }

    private void updateRouting(Routing routing) {
        // created_by / created_at 落库后不可变，UPDATE 不触碰
        jdbc.update("UPDATE routing SET product_id = ?, version = ?, status = ?, remark = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                routing.getProductId(), routing.getVersion(), routing.getStatus().name(),
                routing.getRemark(), routing.getUpdatedBy(), toDb(routing.getUpdatedAt()),
                routing.getId());
    }

    private void insertOperations(Routing routing) {
        List<RoutingOperation> operations = routing.getOperations();
        if (operations.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO routing_operation "
                        + "(routing_id, sequence_no, operation_name, standard_hours, work_center, cost_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                operations, operations.size(), (ps, op) -> {
                    ps.setLong(1, routing.getId());
                    ps.setInt(2, op.sequenceNo());
                    ps.setString(3, op.operationName());
                    ps.setBigDecimal(4, op.standardHours());
                    // work_center 可为 NULL
                    if (op.workCenter() != null) {
                        ps.setString(5, op.workCenter());
                    } else {
                        ps.setNull(5, Types.VARCHAR);
                    }
                    // cost_rate 可为 NULL
                    if (op.costRate() != null) {
                        ps.setBigDecimal(6, op.costRate());
                    } else {
                        ps.setNull(6, Types.DECIMAL);
                    }
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Routing> findById(long id) {
        List<RoutingRow> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<RoutingOperation> operations = jdbc.query(
                "SELECT sequence_no, operation_name, standard_hours, work_center, cost_rate "
                        + "FROM routing_operation WHERE routing_id = ? ORDER BY sequence_no",
                OPERATION_ROW_MAPPER, id);
        return Optional.of(restore(rows.get(0), operations));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Routing> findByProductAndVersion(long productId, int version) {
        List<RoutingRow> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE product_id = ? AND version = ?",
                ROW_MAPPER, productId, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RoutingRow row = rows.get(0);
        List<RoutingOperation> operations = jdbc.query(
                "SELECT sequence_no, operation_name, standard_hours, work_center, cost_rate "
                        + "FROM routing_operation WHERE routing_id = ? ORDER BY sequence_no",
                OPERATION_ROW_MAPPER, row.id());
        return Optional.of(restore(row, operations));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Routing> findEnabledByProductId(long productId) {
        List<RoutingRow> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE product_id = ? AND status = 'ENABLED' ORDER BY version",
                ROW_MAPPER, productId);
        return attachOperations(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Routing> search(RoutingQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.productId() != null) {
            where.append("AND product_id = ? ");
            args.add(query.productId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routing " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((long) (query.page() - 1) * query.size());
        List<RoutingRow> rows = jdbc.query(
                SELECT_COLUMNS + where + "ORDER BY product_id, version DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(attachOperations(rows), totalCount, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Routing> findActiveByProductId(long productId) {
        // active_flag 生成列保证同产品至多一条 ENABLED；直接按 status 查与生成列等价且索引友好
        List<RoutingRow> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE product_id = ? AND status = 'ENABLED'",
                ROW_MAPPER, productId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RoutingRow row = rows.get(0);
        List<RoutingOperation> operations = jdbc.query(
                "SELECT sequence_no, operation_name, standard_hours, work_center, cost_rate "
                        + "FROM routing_operation WHERE routing_id = ? ORDER BY sequence_no",
                OPERATION_ROW_MAPPER, row.id());
        return Optional.of(restore(row, operations));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByProductAndVersion(long productId, int version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routing WHERE product_id = ? AND version = ?",
                Integer.class, productId, version);
        return count != null && count > 0;
    }

    /**
     * 批量回带工序行（一次 IN 查询，避免 N+1）。
     * rows 为空时直接返回空列表。
     */
    private List<Routing> attachOperations(List<RoutingRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(RoutingRow::id).toArray();
        Map<Long, List<RoutingOperation>> byRouting = new HashMap<>();
        jdbc.query(
                "SELECT routing_id, sequence_no, operation_name, standard_hours, work_center, cost_rate "
                        + "FROM routing_operation WHERE routing_id IN (" + placeholders + ") ORDER BY routing_id, sequence_no",
                rs -> {
                    byRouting.computeIfAbsent(rs.getLong("routing_id"), k -> new ArrayList<>())
                            .add(new RoutingOperation(
                                    rs.getInt("sequence_no"),
                                    rs.getString("operation_name"),
                                    rs.getBigDecimal("standard_hours"),
                                    rs.getObject("work_center", String.class),      // 可为 NULL
                                    rs.getObject("cost_rate", BigDecimal.class)));  // 可为 NULL
                }, ids);
        return rows.stream()
                .map(row -> restore(row, byRouting.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private static Routing restore(RoutingRow row, List<RoutingOperation> operations) {
        return Routing.restore(
                row.id(), row.productId(), row.version(), row.status(), row.remark(),
                operations, row.createdBy(), row.createdAt(), row.updatedBy(), row.updatedAt());
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
