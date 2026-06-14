package com.sjherp.infra.persistence.production;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.DemandPlan;
import com.sjherp.domain.production.DemandPlanLine;
import com.sjherp.domain.production.DemandPlanQuery;
import com.sjherp.domain.production.DemandPlanRepository;

/**
 * 需求计划 MySQL 持久化（M5-T02）。
 *
 * <p>头行分离两步写：先插头行取自增 id，再批量插行（值对象无独立生命周期，行整体替换——先删后插）。
 * 读取时先查头，再按 id 批量回带行，最后通过 {@code DemandPlan.restore} 重建完整聚合
 * （避免中间态：行与头一起传入工厂方法，无需 setLines）。
 * 时间列全程 UTC DATETIME(6)，{@code LocalDateTime} ↔ {@code Instant} 经 {@code ZoneOffset.UTC} 转换。
 */
@Transactional
public class JdbcDemandPlanRepository implements DemandPlanRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, plan_date, status, remark, "
                    + "created_by, created_at, updated_by, updated_at FROM demand_plan ";

    /** 头行中间载体（行单独查询后再 restore 成聚合） */
    private record HeadRow(long id, String docNo, java.time.LocalDate planDate,
                           ArchiveStatus status, String remark,
                           String createdBy, Instant createdAt,
                           String updatedBy, Instant updatedAt) {
    }

    private static final RowMapper<HeadRow> HEAD_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getDate("plan_date").toLocalDate(),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("remark"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static final RowMapper<DemandPlanLine> LINE_MAPPER = (rs, rowNum) -> new DemandPlanLine(
            rs.getLong("product_id"),
            rs.getBigDecimal("quantity"),
            rs.getLong("unit_id"),
            rs.getDate("due_date") != null ? rs.getDate("due_date").toLocalDate() : null);

    private final JdbcTemplate jdbc;

    public JdbcDemandPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(DemandPlan plan) {
        if (plan.getId() == null) {
            insert(plan);
        } else {
            update(plan);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DemandPlan> findByDocNo(String docNo) {
        List<HeadRow> rows = jdbc.query(SELECT_HEAD + "WHERE doc_no = ?", HEAD_MAPPER, docNo);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(restore(rows.get(0), queryLines(rows.get(0).id())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DemandPlan> search(DemandPlanQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM demand_plan " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((long) (query.page() - 1) * query.size());
        List<HeadRow> rows = jdbc.query(
                SELECT_HEAD + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, pageArgs.toArray());

        return new PageResult<>(attachLines(rows), totalCount, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DemandPlan> findAllEnabled() {
        List<HeadRow> rows = jdbc.query(
                SELECT_HEAD + "WHERE status = 'ENABLED' ORDER BY id",
                HEAD_MAPPER);
        return attachLines(rows);
    }

    // ================================================================ 私有辅助

    private void insert(DemandPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO demand_plan "
                            + "(doc_no, plan_date, status, remark, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.getDocNo());
            ps.setDate(2, Date.valueOf(plan.getPlanDate()));
            ps.setString(3, plan.getStatus().name());
            ps.setString(4, plan.getRemark());
            ps.setString(5, plan.getCreatedBy());
            ps.setObject(6, toDb(plan.getCreatedAt()));
            ps.setString(7, plan.getUpdatedBy());
            ps.setObject(8, toDb(plan.getUpdatedAt()));
            return ps;
        }, keyHolder);
        plan.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得需求计划自增主键").longValue());
        insertLines(plan);
    }

    private void update(DemandPlan plan) {
        // created_by / created_at 落库后不可变，UPDATE 不触碰
        jdbc.update("UPDATE demand_plan SET plan_date = ?, status = ?, remark = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                Date.valueOf(plan.getPlanDate()),
                plan.getStatus().name(),
                plan.getRemark(),
                plan.getUpdatedBy(),
                toDb(plan.getUpdatedAt()),
                plan.getId());
        // 行整体替换：先清空再重插（值对象无独立生命周期）
        jdbc.update("DELETE FROM demand_plan_line WHERE demand_plan_id = ?", plan.getId());
        insertLines(plan);
    }

    private void insertLines(DemandPlan plan) {
        List<DemandPlanLine> lines = plan.getLines();
        if (lines.isEmpty()) {
            return;
        }
        final long planId = plan.getId();
        AtomicInteger counter = new AtomicInteger(0);
        jdbc.batchUpdate(
                "INSERT INTO demand_plan_line "
                        + "(tenant_id, demand_plan_id, line_no, product_id, quantity, unit_id, due_date) "
                        + "VALUES (0, ?, ?, ?, ?, ?, ?)",
                lines, lines.size(), (ps, line) -> {
                    int lineNo = counter.incrementAndGet();
                    ps.setLong(1, planId);
                    ps.setInt(2, lineNo);
                    ps.setLong(3, line.productId());
                    ps.setBigDecimal(4, line.quantity());
                    ps.setLong(5, line.unitId());
                    ps.setObject(6, line.dueDate() != null ? Date.valueOf(line.dueDate()) : null);
                });
    }

    /**
     * 批量回带行（一次 IN 查询，避免 N+1）。
     * rows 为空时直接返回空列表。
     */
    private List<DemandPlan> attachLines(List<HeadRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(HeadRow::id).toArray();
        Map<Long, List<DemandPlanLine>> byPlan = new HashMap<>();
        jdbc.query(
                "SELECT demand_plan_id, product_id, quantity, unit_id, due_date "
                        + "FROM demand_plan_line WHERE demand_plan_id IN (" + placeholders + ") "
                        + "ORDER BY demand_plan_id, line_no",
                rs -> {
                    byPlan.computeIfAbsent(rs.getLong("demand_plan_id"), k -> new ArrayList<>())
                            .add(new DemandPlanLine(
                                    rs.getLong("product_id"),
                                    rs.getBigDecimal("quantity"),
                                    rs.getLong("unit_id"),
                                    rs.getDate("due_date") != null
                                            ? rs.getDate("due_date").toLocalDate() : null));
                }, ids);
        return rows.stream()
                .map(row -> restore(row, byPlan.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private List<DemandPlanLine> queryLines(long planId) {
        return jdbc.query(
                "SELECT product_id, quantity, unit_id, due_date "
                        + "FROM demand_plan_line WHERE demand_plan_id = ? ORDER BY line_no",
                LINE_MAPPER, planId);
    }

    private static DemandPlan restore(HeadRow row, List<DemandPlanLine> lines) {
        return DemandPlan.restore(
                row.id(), row.docNo(), row.planDate(), row.status(), row.remark(),
                lines, row.createdBy(), row.createdAt(), row.updatedBy(), row.updatedAt());
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
