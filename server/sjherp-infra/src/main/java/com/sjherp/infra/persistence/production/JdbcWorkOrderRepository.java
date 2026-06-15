package com.sjherp.infra.persistence.production;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.WorkOrder;
import com.sjherp.domain.production.WorkOrderQuery;
import com.sjherp.domain.production.WorkOrderRepository;
import com.sjherp.domain.production.WorkOrderSourceType;

/**
 * 工单 MySQL 持久化（M5-T03）。
 *
 * <p>工单当前批次无明细行（行明细留 T04 领料/T05 完工），
 * save() 按 id 是否存在决定 INSERT 或 UPDATE（状态+审计字段）。
 * 时间列全程 UTC DATETIME(6)，{@code LocalDateTime} ↔ {@code Instant} 经 {@code ZoneOffset.UTC} 转换。
 */
@Transactional
public class JdbcWorkOrderRepository implements WorkOrderRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, product_id, planned_qty, unit_id, completed_qty, "
                    + "bom_version, routing_version, warehouse_id, mrp_run_doc_no, "
                    + "source_type, planned_start_date, planned_end_date, remark, "
                    + "status, created_by FROM work_order ";

    /** 工单头中间载体 */
    private record HeadRow(
            long id, String docNo, long productId, java.math.BigDecimal plannedQty,
            long unitId, java.math.BigDecimal completedQty,
            Integer bomVersion, Integer routingVersion, Long warehouseId,
            String mrpRunDocNo, WorkOrderSourceType sourceType,
            LocalDate plannedStartDate, LocalDate plannedEndDate,
            String remark, DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_MAPPER = (rs, rowNum) -> {
        Integer bomVersion = (Integer) rs.getObject("bom_version");
        Integer routingVersion = (Integer) rs.getObject("routing_version");
        Long warehouseId = rs.getObject("warehouse_id") == null ? null : rs.getLong("warehouse_id");
        java.sql.Date startDate = rs.getDate("planned_start_date");
        java.sql.Date endDate = rs.getDate("planned_end_date");
        return new HeadRow(
                rs.getLong("id"),
                rs.getString("doc_no"),
                rs.getLong("product_id"),
                rs.getBigDecimal("planned_qty"),
                rs.getLong("unit_id"),
                rs.getBigDecimal("completed_qty"),
                bomVersion,
                routingVersion,
                warehouseId,
                rs.getString("mrp_run_doc_no"),
                WorkOrderSourceType.valueOf(rs.getString("source_type")),
                startDate != null ? startDate.toLocalDate() : null,
                endDate != null ? endDate.toLocalDate() : null,
                rs.getString("remark"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"));
    };

    private final JdbcTemplate jdbc;

    public JdbcWorkOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(WorkOrder wo) {
        if (wo.getId() == null) {
            insert(wo);
        } else {
            update(wo);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkOrder> findByDocNo(String docNo) {
        List<HeadRow> rows = jdbc.query(SELECT_HEAD + "WHERE doc_no = ?", HEAD_MAPPER, docNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(restore(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<WorkOrder> search(WorkOrderQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (query.productId() != null) {
            where.append("AND product_id = ? ");
            params.add(query.productId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            params.add(query.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order " + where, Long.class, params.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        params.add(query.size());
        params.add((long) (query.page() - 1) * query.size());
        List<HeadRow> rows = jdbc.query(
                SELECT_HEAD + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, params.toArray());

        List<WorkOrder> items = rows.stream().map(this::restore).toList();
        return new PageResult<>(items, totalCount, query.page(), query.size());
    }

    // ================================================================ 私有辅助

    private void insert(WorkOrder wo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = toDb(Instant.now());
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO work_order "
                            + "(tenant_id, doc_no, product_id, planned_qty, unit_id, completed_qty, "
                            + "bom_version, routing_version, warehouse_id, mrp_run_doc_no, source_type, "
                            + "planned_start_date, planned_end_date, remark, status, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, wo.getDocNo());
            ps.setLong(2, wo.getProductId());
            ps.setBigDecimal(3, wo.getPlannedQty());
            ps.setLong(4, wo.getUnitId());
            ps.setBigDecimal(5, wo.getCompletedQty());
            ps.setObject(6, wo.getBomVersion());
            ps.setObject(7, wo.getRoutingVersion());
            ps.setObject(8, wo.getWarehouseId());
            ps.setString(9, wo.getMrpRunDocNo());
            ps.setString(10, wo.getSourceType().name());
            ps.setObject(11, wo.getPlannedStartDate());
            ps.setObject(12, wo.getPlannedEndDate());
            ps.setString(13, wo.getRemark());
            ps.setString(14, wo.getStatus().name());
            ps.setString(15, wo.getCreatedBy());
            ps.setObject(16, now);
            ps.setString(17, wo.getCreatedBy());
            ps.setObject(18, now);
            return ps;
        }, keyHolder);
        wo.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得工单自增主键").longValue());
    }

    private void update(WorkOrder wo) {
        LocalDateTime now = toDb(Instant.now());
        // 持久化冲销链路标记（reversal_of_id/reversed_by_id），与全部兄弟单据范式一致
        // （评审 P1：原实现遗漏列致冲销关联静默丢失，违反可审计原则）。
        jdbc.update(
                "UPDATE work_order SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                wo.getStatus().name(),
                wo.getReversalOfId(),
                wo.getReversedById(),
                wo.getUpdatedBy(),
                now,
                wo.getId());
    }

    private WorkOrder restore(HeadRow row) {
        return WorkOrder.restore(
                row.id(), row.docNo(), row.productId(), row.plannedQty(),
                row.unitId(), row.completedQty(), row.bomVersion(),
                row.routingVersion(), row.warehouseId(), row.mrpRunDocNo(),
                row.sourceType(), row.plannedStartDate(), row.plannedEndDate(),
                row.remark(), row.status(), row.createdBy());
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
