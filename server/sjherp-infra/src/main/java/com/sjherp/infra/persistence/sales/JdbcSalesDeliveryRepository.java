package com.sjherp.infra.persistence.sales;

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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.sales.SalesDeliveryQuery;
import com.sjherp.domain.sales.SalesDeliveryRepository;

/**
 * 销售出库单仓储的 MySQL 实现（M3-T09，代码风格照 {@code JdbcTransferRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段，
 * <b>并逐行更新 COGS</b>（cogs_amount 由过账回填，更新时必须落库）。
 *
 * <p>tenant_id v1.0 恒 0（ADR-002）。时间列 DATETIME(6) 一律按 UTC 读写。
 */
@Transactional
public class JdbcSalesDeliveryRepository implements SalesDeliveryRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, sales_order_no, warehouse_id, remark, status, created_by "
                    + "FROM sales_delivery ";

    private final JdbcTemplate jdbc;

    public JdbcSalesDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(SalesDelivery delivery) {
        Long headId = findHeadId(delivery.getDocNo());
        if (headId == null) {
            insert(delivery);
        } else {
            update(headId, delivery);
        }
    }

    private void insert(SalesDelivery delivery) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sales_delivery (doc_no, sales_order_no, warehouse_id, remark, status, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, delivery.getDocNo());
            ps.setString(2, delivery.getSalesOrderNo());
            ps.setLong(3, delivery.getWarehouseId());
            ps.setString(4, delivery.getRemark());
            ps.setString(5, delivery.getStatus().name());
            ps.setString(6, delivery.getCreatedBy());
            ps.setObject(7, toDb(delivery.getCreatedAt()));
            ps.setString(8, delivery.getUpdatedBy());
            ps.setObject(9, toDb(delivery.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得出库单头自增主键").longValue();

        for (SalesDeliveryLine line : delivery.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, SalesDeliveryLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sales_delivery_line (sales_delivery_id, line_no, so_line_no, "
                            + "product_id, quantity, cogs_amount, invoiced_qty) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setInt(3, line.getSoLineNo());
            ps.setLong(4, line.getProductId());
            ps.setBigDecimal(5, line.getQuantity());
            ps.setBigDecimal(6, line.getCogsAmount());
            ps.setBigDecimal(7, line.getInvoicedQty());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得出库单行自增主键").longValue());
    }

    private void update(long headId, SalesDelivery delivery) {
        jdbc.update("UPDATE sales_delivery SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                delivery.getStatus().name(), delivery.getReversalOfId(), delivery.getReversedById(),
                delivery.getUpdatedBy(), toDb(delivery.getUpdatedAt()), headId);
        // COGS 与累计已开票量 invoiced_qty 均由过账回填（COGS 出库过账、invoiced_qty 发票过账），更新时逐行落库
        for (SalesDeliveryLine line : delivery.getLines()) {
            jdbc.update("UPDATE sales_delivery_line SET cogs_amount = ?, invoiced_qty = ? "
                            + "WHERE tenant_id = 0 AND sales_delivery_id = ? AND line_no = ?",
                    line.getCogsAmount(), line.getInvoicedQty(), headId, line.getLineNo());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesDelivery> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDelivery(heads.get(0)));
    }

    @Override
    public Optional<SalesDelivery> findByDocNoForUpdate(String docNo) {
        List<HeadRow> heads = jdbc.query(
                SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ? FOR UPDATE",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDelivery(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SalesDelivery> search(SalesDeliveryQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.salesOrderNo() != null) {
            where.append("AND sales_order_no = ? ");
            args.add(query.salesOrderNo());
        }
        if (query.warehouseId() != null) {
            where.append("AND warehouse_id = ? ");
            args.add(query.warehouseId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }
        if (query.invoiceableOnly()) {
            where.append("AND status = 'COMPLETED' ")
                    .append("AND EXISTS (SELECT 1 FROM sales_delivery_line invoiceable_line ")
                    .append("WHERE invoiceable_line.tenant_id = sales_delivery.tenant_id ")
                    .append("AND invoiceable_line.sales_delivery_id = sales_delivery.id ")
                    .append("AND invoiceable_line.quantity > invoiceable_line.invoiced_qty) ");
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sales_delivery " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                HEAD_ROW_MAPPER, pageArgs.toArray());

        List<SalesDelivery> deliveries = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            deliveries.add(toDelivery(head));
        }
        return new PageResult<>(deliveries, totalCount, query.page(), query.size());
    }

    private SalesDelivery toDelivery(HeadRow head) {
        List<SalesDeliveryLine> lines = loadLines(head.id());
        return SalesDelivery.restore(head.docNo(), head.salesOrderNo(), head.warehouseId(),
                head.remark(), head.status(), lines, head.createdBy());
    }

    private List<SalesDeliveryLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, so_line_no, product_id, quantity, cogs_amount, invoiced_qty "
                        + "FROM sales_delivery_line WHERE tenant_id = 0 AND sales_delivery_id = ? ORDER BY line_no",
                (rs, rowNum) -> SalesDeliveryLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getInt("so_line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("cogs_amount"),
                        rs.getBigDecimal("invoiced_qty")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM sales_delivery WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private record HeadRow(long id, String docNo, String salesOrderNo, long warehouseId,
                           String remark, DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getString("sales_order_no"),
            rs.getLong("warehouse_id"),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
