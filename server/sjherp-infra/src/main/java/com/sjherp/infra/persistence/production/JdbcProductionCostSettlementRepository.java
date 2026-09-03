package com.sjherp.infra.persistence.production;

import java.math.BigDecimal;
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
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLine;
import com.sjherp.domain.production.ProductionCostSettlementQuery;
import com.sjherp.domain.production.ProductionCostSettlementRepository;

/**
 * 月末成本结转单 MySQL 仓储实现（M5-T06）。
 *
 * <p>照 {@link JdbcProductionReportRepository} 范式：头行分开保存；行先删后插；
 * 分页 COUNT+LIMIT/OFFSET；页码从 1 起；TENANT_ID = 0L；时间列 UTC DATETIME(6)。
 */
@Transactional
public class JdbcProductionCostSettlementRepository implements ProductionCostSettlementRepository {

    private static final long TENANT_ID = 0L;

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, period, remark, status, reversal_of_id, reversed_by_id, "
                    + "created_by, updated_by FROM production_cost_settlement ";

    private final JdbcTemplate jdbc;

    public JdbcProductionCostSettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- save

    @Override
    public void save(ProductionCostSettlement settlement) {
        if (settlement.getId() == null) {
            insert(settlement);
        } else {
            update(settlement);
        }
        saveLines(settlement);
    }

    @Override
    public BigDecimal sumTransferredLaborOverheadByWorkOrder(String workOrderDocNo) {
        // 工费已结转锚点：仅统计 COMPLETED 结转单行的完工工费（completed_cost − material_cost）
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(l.completed_cost - l.material_cost), 0) "
                        + "FROM production_cost_settlement_line l "
                        + "JOIN production_cost_settlement h ON h.id = l.settlement_id "
                        + "AND h.tenant_id = l.tenant_id "
                        + "WHERE l.tenant_id = ? AND l.work_order_doc_no = ? AND h.status = ?",
                BigDecimal.class,
                TENANT_ID, workOrderDocNo, DocumentStatus.COMPLETED.name());
        return sum != null ? sum : BigDecimal.ZERO;
    }

    @Override
    public PriorCumulative priorCumulativeByWorkOrder(String workOrderDocNo,
                                                      String excludeSettlementDocNo) {
        // 累计已过账（COMPLETED）结转行的料/工/费/完工成本，排除当前结转单自身（过账时本单已 COMPLETED）
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(l.raw_material_cost), 0) AS raw_m, "
                        + "COALESCE(SUM(l.goods_material_cost), 0) AS goods_m, "
                        + "COALESCE(SUM(l.labor_cost), 0)     AS la, "
                        + "COALESCE(SUM(l.overhead_cost), 0)  AS o, "
                        + "COALESCE(SUM(l.completed_cost), 0) AS c "
                        + "FROM production_cost_settlement_line l "
                        + "JOIN production_cost_settlement h ON h.id = l.settlement_id "
                        + "AND h.tenant_id = l.tenant_id "
                        + "WHERE l.tenant_id = ? AND l.work_order_doc_no = ? "
                        + "AND h.status = ? AND h.doc_no <> ?",
                (rs, rn) -> new PriorCumulative(
                        rs.getBigDecimal("raw_m"), rs.getBigDecimal("goods_m"),
                        rs.getBigDecimal("la"), rs.getBigDecimal("o"), rs.getBigDecimal("c")),
                TENANT_ID, workOrderDocNo, DocumentStatus.COMPLETED.name(), excludeSettlementDocNo);
    }

    private void insert(ProductionCostSettlement settlement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = toDb(Instant.now());
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO production_cost_settlement "
                            + "(tenant_id, doc_no, period, remark, status, "
                            + "reversal_of_id, reversed_by_id, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, TENANT_ID);
            ps.setString(2, settlement.getDocNo());
            ps.setString(3, settlement.getPeriod());
            ps.setString(4, settlement.getRemark());
            ps.setString(5, settlement.getStatus().name());
            ps.setString(6, settlement.getReversalOfId());
            ps.setString(7, settlement.getReversedById());
            ps.setString(8, settlement.getCreatedBy());
            ps.setObject(9, now);
            ps.setString(10, settlement.getCreatedBy());
            ps.setObject(11, now);
            return ps;
        }, keyHolder);
        settlement.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得成本结转单自增主键").longValue());
    }

    private void update(ProductionCostSettlement settlement) {
        LocalDateTime now = toDb(Instant.now());
        jdbc.update(
                "UPDATE production_cost_settlement SET status=?, remark=?, "
                        + "reversal_of_id=?, reversed_by_id=?, updated_by=?, updated_at=? "
                        + "WHERE tenant_id=? AND id=?",
                settlement.getStatus().name(),
                settlement.getRemark(),
                settlement.getReversalOfId(),
                settlement.getReversedById(),
                settlement.getUpdatedBy(),
                now,
                TENANT_ID,
                settlement.getId());
    }

    /** 行先删后插——全量替换（过账后回填字段随之持久化）。 */
    private void saveLines(ProductionCostSettlement settlement) {
        long headId = settlement.getId();
        jdbc.update(
                "DELETE FROM production_cost_settlement_line WHERE tenant_id=? AND settlement_id=?",
                TENANT_ID, headId);

        for (ProductionCostSettlementLine line : settlement.getLines()) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO production_cost_settlement_line "
                                + "(tenant_id, settlement_id, line_no, work_order_doc_no, "
                                + "material_cost, raw_material_cost, goods_material_cost, labor_cost, overhead_cost, completed_qty, "
                                + "completed_cost, wip_qty, wip_completion_pct, wip_cost, "
                                + "already_transferred, cost_adjust_idem_key, voucher_doc_no) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, TENANT_ID);
                ps.setLong(2, headId);
                ps.setInt(3, line.getLineNo());
                ps.setString(4, line.getWorkOrderDocNo());
                ps.setBigDecimal(5, line.getMaterialCost());
                ps.setBigDecimal(6, line.getRawMaterialCost());
                ps.setBigDecimal(7, line.getGoodsMaterialCost());
                ps.setBigDecimal(8, line.getLaborCost());
                ps.setBigDecimal(9, line.getOverheadCost());
                ps.setBigDecimal(10, line.getCompletedQty());
                ps.setBigDecimal(11, line.getCompletedCost());
                ps.setBigDecimal(12, line.getWipQty());
                ps.setBigDecimal(13, line.getWipCompletionPct());
                ps.setBigDecimal(14, line.getWipCost());
                ps.setBigDecimal(15, line.getAlreadyTransferred());
                ps.setString(16, line.getCostAdjustIdemKey());
                ps.setString(17, line.getVoucherDocNo());
                return ps;
            }, keyHolder);
            if (line.getId() == null) {
                line.assignId(Objects.requireNonNull(keyHolder.getKey(),
                        "未取得成本结转单行自增主键").longValue());
            }
        }
    }

    // ---------------------------------------------------------------- findByDocNo

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductionCostSettlement> findByDocNo(String docNo) {
        List<HeadRow> rows = jdbc.query(SELECT_HEAD + "WHERE tenant_id=? AND doc_no=?",
                (rs, rn) -> mapHead(rs), TENANT_ID, docNo);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        HeadRow head = rows.get(0);
        return Optional.of(toAggregate(head, loadLines(head.id())));
    }

    // ---------------------------------------------------------------- search

    @Override
    @Transactional(readOnly = true)
    public PageResult<ProductionCostSettlement> search(ProductionCostSettlementQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id=? ");
        List<Object> params = new ArrayList<>();
        params.add(TENANT_ID);

        if (query.period() != null) {
            where.append("AND period=? ");
            params.add(query.period());
        }
        if (query.status() != null) {
            where.append("AND status=? ");
            params.add(query.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_cost_settlement " + where,
                Long.class, params.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        params.add(query.size());
        params.add((long) (query.page() - 1) * query.size());
        List<HeadRow> heads = jdbc.query(
                SELECT_HEAD + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rn) -> mapHead(rs), params.toArray());

        List<ProductionCostSettlement> items = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            items.add(toAggregate(head, loadLines(head.id())));
        }
        return new PageResult<>(items, totalCount, query.page(), query.size());
    }

    // ---------------------------------------------------------------- 私有辅助

    private List<ProductionCostSettlementLine> loadLines(long headId) {
        return jdbc.query(
                "SELECT id, line_no, work_order_doc_no, material_cost, raw_material_cost, goods_material_cost, "
                        + "labor_cost, overhead_cost, "
                        + "completed_qty, completed_cost, wip_qty, wip_completion_pct, wip_cost, "
                        + "already_transferred, cost_adjust_idem_key, voucher_doc_no "
                        + "FROM production_cost_settlement_line "
                        + "WHERE tenant_id=? AND settlement_id=? ORDER BY line_no",
                (rs, rn) -> ProductionCostSettlementLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getString("work_order_doc_no"),
                        rs.getBigDecimal("raw_material_cost"),
                        rs.getBigDecimal("goods_material_cost"),
                        rs.getBigDecimal("material_cost"),
                        rs.getBigDecimal("labor_cost"),
                        rs.getBigDecimal("overhead_cost"),
                        rs.getBigDecimal("completed_qty"),
                        rs.getBigDecimal("completed_cost"),
                        rs.getBigDecimal("wip_qty"),
                        rs.getBigDecimal("wip_completion_pct"),
                        rs.getBigDecimal("wip_cost"),
                        rs.getBigDecimal("already_transferred"),
                        rs.getString("cost_adjust_idem_key"),
                        rs.getString("voucher_doc_no")),
                TENANT_ID, headId);
    }

    private static ProductionCostSettlement toAggregate(HeadRow head,
                                                        List<ProductionCostSettlementLine> lines) {
        return ProductionCostSettlement.restore(
                head.id(), head.docNo(), head.period(), head.remark(),
                head.status(), head.reversalOfId(), head.reversedById(),
                lines, head.createdBy(), head.updatedBy());
    }

    private HeadRow mapHead(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new HeadRow(
                rs.getLong("id"),
                rs.getString("doc_no"),
                rs.getString("period"),
                rs.getString("remark"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("reversal_of_id"),
                rs.getString("reversed_by_id"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }

    private record HeadRow(long id, String docNo, String period, String remark, DocumentStatus status,
                           String reversalOfId, String reversedById, String createdBy, String updatedBy) {
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
