package com.sjherp.infra.persistence.production;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLine;
import com.sjherp.domain.production.ProductionReportQuery;
import com.sjherp.domain.production.ProductionReportRepository;

/**
 * 报工单 MySQL 仓储实现（M5-T05）。
 *
 * <p>照 {@link JdbcMaterialIssueRepository} 范式：头行分开保存；行先删后插；
 * 分页查询 COUNT+LIMIT/OFFSET；页码从 1 起；TENANT_ID = 0L；
 * 时间列 UTC DATETIME(6)；BigDecimal 精度来自 DB DECIMAL 定义。
 */
@Transactional
public class JdbcProductionReportRepository implements ProductionReportRepository {

    private static final long TENANT_ID = 0L;

    /** 报工单头 SELECT 公共前缀 */
    private static final String SELECT_HEAD =
            "SELECT id, doc_no, work_order_doc_no, warehouse_id, product_id, "
                    + "completed_qty, scrap_qty, unit_id, inbound_cost, remark, status, "
                    + "reversal_of_id, reversed_by_id, created_by, updated_by "
                    + "FROM production_report ";

    private final JdbcTemplate jdbc;

    public JdbcProductionReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- save

    @Override
    public void save(ProductionReport report) {
        if (report.getId() == null) {
            insert(report);
        } else {
            update(report);
        }
        saveLines(report);
    }

    @Override
    public java.math.BigDecimal sumInboundCostByWorkOrder(String workOrderDocNo) {
        // 已结转料费锚点：仅统计 COMPLETED 报工单的 inbound_cost（防分批完工重复入账，评审 P0）
        java.math.BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(inbound_cost), 0) FROM production_report "
                        + "WHERE tenant_id = ? AND work_order_doc_no = ? AND status = ?",
                java.math.BigDecimal.class,
                TENANT_ID, workOrderDocNo, DocumentStatus.COMPLETED.name());
        return sum != null ? sum : java.math.BigDecimal.ZERO;
    }

    private void insert(ProductionReport report) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = toDb(Instant.now());
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO production_report "
                            + "(tenant_id, doc_no, work_order_doc_no, warehouse_id, product_id, "
                            + "completed_qty, scrap_qty, unit_id, inbound_cost, remark, status, "
                            + "reversal_of_id, reversed_by_id, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, TENANT_ID);
            ps.setString(2, report.getDocNo());
            ps.setString(3, report.getWorkOrderDocNo());
            ps.setLong(4, report.getWarehouseId());
            ps.setLong(5, report.getProductId());
            ps.setBigDecimal(6, report.getCompletedQty());
            ps.setBigDecimal(7, report.getScrapQty());
            ps.setLong(8, report.getUnitId());
            // inbound_cost 过账前为 null
            if (report.getInboundCost() != null) {
                ps.setBigDecimal(9, report.getInboundCost());
            } else {
                ps.setNull(9, java.sql.Types.DECIMAL);
            }
            ps.setString(10, report.getRemark());
            ps.setString(11, report.getStatus().name());
            ps.setString(12, report.getReversalOfId());
            ps.setString(13, report.getReversedById());
            ps.setString(14, report.getCreatedBy());
            ps.setObject(15, now);
            ps.setString(16, report.getCreatedBy());
            ps.setObject(17, now);
            return ps;
        }, keyHolder);
        report.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得报工单自增主键").longValue());
    }

    private void update(ProductionReport report) {
        LocalDateTime now = toDb(Instant.now());
        // inbound_cost 过账后回填；状态、冲销链路均可变
        jdbc.update(
                "UPDATE production_report SET status=?, inbound_cost=?, "
                        + "reversal_of_id=?, reversed_by_id=?, "
                        + "updated_by=?, updated_at=? WHERE tenant_id=? AND id=?",
                report.getStatus().name(),
                report.getInboundCost(),   // null → DB NULL
                report.getReversalOfId(),
                report.getReversedById(),
                report.getUpdatedBy(),
                now,
                TENANT_ID,
                report.getId());
    }

    /**
     * 行先删后插——全量替换，简化乐观锁复杂度。
     * 过账后聚合根行状态不变（工时行过账前后内容不变），先删后插保证幂等。
     */
    private void saveLines(ProductionReport report) {
        long headId = report.getId();

        // 删除旧行
        jdbc.update(
                "DELETE FROM production_report_line WHERE tenant_id=? AND production_report_id=?",
                TENANT_ID, headId);

        // 批量插入新行
        for (ProductionReportLine line : report.getLines()) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO production_report_line "
                                + "(tenant_id, production_report_id, line_no, "
                                + "operation_seq_no, operation_name, work_center, "
                                + "reported_hours, reported_qty, unit_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, TENANT_ID);
                ps.setLong(2, headId);
                ps.setInt(3, line.getLineNo());
                if (line.getOperationSeqNo() != null) {
                    ps.setInt(4, line.getOperationSeqNo());
                } else {
                    ps.setNull(4, java.sql.Types.INTEGER);
                }
                ps.setString(5, line.getOperationName());
                ps.setString(6, line.getWorkCenter());
                ps.setBigDecimal(7, line.getReportedHours());
                if (line.getReportedQty() != null) {
                    ps.setBigDecimal(8, line.getReportedQty());
                } else {
                    ps.setNull(8, java.sql.Types.DECIMAL);
                }
                ps.setLong(9, line.getUnitId());
                return ps;
            }, keyHolder);
            if (line.getId() == null) {
                line.assignId(Objects.requireNonNull(keyHolder.getKey(),
                        "未取得报工单行自增主键").longValue());
            }
        }
    }

    // ---------------------------------------------------------------- findByDocNo

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductionReport> findByDocNo(String docNo) {
        List<ProductionReport> rows = jdbc.query(
                SELECT_HEAD + "WHERE tenant_id=? AND doc_no=?",
                (rs, rn) -> mapHead(rs), TENANT_ID, docNo);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ProductionReport head = rows.get(0);
        List<ProductionReportLine> lines = loadLines(head.getId());
        return Optional.of(ProductionReport.restore(
                head.getId(),
                head.getDocNo(),
                head.getWorkOrderDocNo(),
                head.getWarehouseId(),
                head.getProductId(),
                head.getCompletedQty(),
                head.getScrapQty(),
                head.getUnitId(),
                head.getInboundCost(),
                head.getRemark(),
                head.getStatus(),
                head.getReversalOfId(),
                head.getReversedById(),
                lines,
                head.getCreatedBy(),
                head.getUpdatedBy()));
    }

    // ---------------------------------------------------------------- search

    @Override
    @Transactional(readOnly = true)
    public PageResult<ProductionReport> search(ProductionReportQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id=? ");
        List<Object> params = new ArrayList<>();
        params.add(TENANT_ID);

        if (query.workOrderDocNo() != null) {
            where.append("AND work_order_doc_no=? ");
            params.add(query.workOrderDocNo());
        }
        if (query.status() != null) {
            where.append("AND status=? ");
            params.add(query.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_report " + where,
                Long.class, params.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        params.add(query.size());
        params.add((long) (query.page() - 1) * query.size());
        List<ProductionReport> heads = jdbc.query(
                SELECT_HEAD + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rn) -> mapHead(rs), params.toArray());

        // 批量加载行（N+1 小企业页面量小，可接受）
        List<ProductionReport> fullItems = new ArrayList<>(heads.size());
        for (ProductionReport head : heads) {
            List<ProductionReportLine> lines = loadLines(head.getId());
            fullItems.add(ProductionReport.restore(
                    head.getId(),
                    head.getDocNo(),
                    head.getWorkOrderDocNo(),
                    head.getWarehouseId(),
                    head.getProductId(),
                    head.getCompletedQty(),
                    head.getScrapQty(),
                    head.getUnitId(),
                    head.getInboundCost(),
                    head.getRemark(),
                    head.getStatus(),
                    head.getReversalOfId(),
                    head.getReversedById(),
                    lines,
                    head.getCreatedBy(),
                    head.getUpdatedBy()));
        }
        return new PageResult<>(fullItems, totalCount, query.page(), query.size());
    }

    // ---------------------------------------------------------------- 私有辅助

    /**
     * 加载指定头 id 下全部行，按行号升序。
     */
    private List<ProductionReportLine> loadLines(long headId) {
        return jdbc.query(
                "SELECT id, line_no, operation_seq_no, operation_name, work_center, "
                        + "reported_hours, reported_qty, unit_id "
                        + "FROM production_report_line "
                        + "WHERE tenant_id=? AND production_report_id=? ORDER BY line_no",
                (rs, rn) -> {
                    Integer seqNo = rs.getObject("operation_seq_no") == null
                            ? null : rs.getInt("operation_seq_no");
                    return ProductionReportLine.restore(
                            rs.getLong("id"),
                            rs.getInt("line_no"),
                            seqNo,
                            rs.getString("operation_name"),
                            rs.getString("work_center"),
                            rs.getBigDecimal("reported_hours"),
                            rs.getBigDecimal("reported_qty"),
                            rs.getLong("unit_id"));
                },
                TENANT_ID, headId);
    }

    /**
     * 从 ResultSet 映射报工单头（不含行）。
     * restore 完整签名含 lines，这里传入空列表；调用方在 loadLines 后重建完整聚合根。
     */
    private ProductionReport mapHead(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ProductionReport.restore(
                rs.getLong("id"),
                rs.getString("doc_no"),
                rs.getString("work_order_doc_no"),
                rs.getLong("warehouse_id"),
                rs.getLong("product_id"),
                rs.getBigDecimal("completed_qty"),
                rs.getBigDecimal("scrap_qty"),
                rs.getLong("unit_id"),
                rs.getBigDecimal("inbound_cost"),  // null when not yet posted
                rs.getString("remark"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("reversal_of_id"),
                rs.getString("reversed_by_id"),
                List.of(),   // 行在 loadLines 步骤加载
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
