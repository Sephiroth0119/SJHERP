package com.sjherp.infra.persistence.sales;

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
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderLine;
import com.sjherp.domain.sales.SalesOrderQuery;
import com.sjherp.domain.sales.SalesOrderRepository;

/**
 * 销售订单仓储的 MySQL 实现（M3-T08，代码风格照 {@code JdbcTransferRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段，
 * <b>并逐行更新累计发货量</b>（delivered_qty 由出库单过账回写，更新时必须落库）。
 *
 * <p>tenant_id v1.0 恒 0（ADR-002），由本层落列，领域层不出现。
 * 时间列 DATETIME(6) 一律按 UTC 读写。
 */
@Transactional
public class JdbcSalesOrderRepository implements SalesOrderRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, customer_id, order_date, remark, status, created_by "
                    + "FROM sales_order ";

    private final JdbcTemplate jdbc;

    public JdbcSalesOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(SalesOrder order) {
        Long headId = findHeadId(order.getDocNo());
        if (headId == null) {
            insert(order);
        } else {
            update(headId, order);
        }
    }

    private void insert(SalesOrder order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sales_order (doc_no, customer_id, order_date, remark, status, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, order.getDocNo());
            ps.setLong(2, order.getCustomerId());
            ps.setObject(3, order.getOrderDate());
            ps.setString(4, order.getRemark());
            ps.setString(5, order.getStatus().name());
            ps.setString(6, order.getCreatedBy());
            ps.setObject(7, toDb(order.getCreatedAt()));
            ps.setString(8, order.getUpdatedBy());
            ps.setObject(9, toDb(order.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得销售订单头自增主键").longValue();

        for (SalesOrderLine line : order.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, SalesOrderLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sales_order_line (sales_order_id, line_no, product_id, quantity, "
                            + "unit_price, amount, delivered_qty) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setLong(3, line.getProductId());
            ps.setBigDecimal(4, line.getQuantity());
            ps.setBigDecimal(5, line.getUnitPrice());
            ps.setBigDecimal(6, line.getAmount());
            ps.setBigDecimal(7, line.getDeliveredQty());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得销售订单行自增主键").longValue());
    }

    private void update(long headId, SalesOrder order) {
        // 头：状态、冲销关联与最后操作审计字段
        jdbc.update("UPDATE sales_order SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                order.getStatus().name(), order.getReversalOfId(), order.getReversedById(),
                order.getUpdatedBy(), toDb(order.getUpdatedAt()), headId);
        // 行：累计发货量由出库单过账回写，更新时逐行落库（其余行内容建单后不变）
        for (SalesOrderLine line : order.getLines()) {
            jdbc.update("UPDATE sales_order_line SET delivered_qty = ? "
                            + "WHERE sales_order_id = ? AND line_no = ?",
                    line.getDeliveredQty(), headId, line.getLineNo());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesOrder> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toOrder(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SalesOrder> search(SalesOrderQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.customerId() != null) {
            where.append("AND customer_id = ? ");
            args.add(query.customerId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }
        if (query.deliverableOnly()) {
            where.append("AND status IN ('APPROVED', 'EXECUTING') ")
                    .append("AND EXISTS (")
                    .append("SELECT 1 FROM sales_order_line sol ")
                    .append("WHERE sol.tenant_id = sales_order.tenant_id ")
                    .append("AND sol.sales_order_id = sales_order.id ")
                    .append("AND sol.quantity > sol.delivered_qty")
                    .append(") ");
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sales_order " + where,
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

        List<SalesOrder> orders = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            orders.add(toOrder(head));
        }
        return new PageResult<>(orders, totalCount, query.page(), query.size());
    }

    private SalesOrder toOrder(HeadRow head) {
        List<SalesOrderLine> lines = loadLines(head.id());
        return SalesOrder.restore(head.docNo(), head.customerId(), head.orderDate(), head.remark(),
                head.status(), lines, head.createdBy());
    }

    private List<SalesOrderLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, product_id, quantity, unit_price, amount, delivered_qty "
                        + "FROM sales_order_line WHERE sales_order_id = ? ORDER BY line_no",
                (rs, rowNum) -> SalesOrderLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("delivered_qty")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM sales_order WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private record HeadRow(long id, String docNo, long customerId, LocalDate orderDate,
                           String remark, DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getLong("customer_id"),
            rs.getObject("order_date", LocalDate.class),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
