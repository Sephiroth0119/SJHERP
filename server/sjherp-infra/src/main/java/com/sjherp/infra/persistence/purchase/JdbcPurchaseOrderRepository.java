package com.sjherp.infra.persistence.purchase;

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
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderLine;
import com.sjherp.domain.purchase.PurchaseOrderQuery;
import com.sjherp.domain.purchase.PurchaseOrderRepository;

/**
 * 采购订单仓储的 MySQL 实现（M3-T05；代码风格照 {@code JdbcTransferRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段
 * <b>并回写各行 received_qty</b>（采购入库过账时累加到货量，更新路径必须落库——区别于调拨单
 * 行内容不变）。tenant_id v1.0 恒 0（ADR-002）；时间列 DATETIME(6) 按 UTC 读写。
 */
@Transactional
public class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, supplier_id, order_date, remark, status, created_by "
                    + "FROM purchase_order ";

    private final JdbcTemplate jdbc;

    public JdbcPurchaseOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PurchaseOrder order) {
        Long headId = findHeadId(order.getDocNo());
        if (headId == null) {
            insert(order);
        } else {
            update(headId, order);
        }
    }

    private void insert(PurchaseOrder order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO purchase_order (doc_no, supplier_id, order_date, remark, "
                            + "status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, order.getDocNo());
            ps.setLong(2, order.getSupplierId());
            ps.setObject(3, order.getOrderDate());
            ps.setString(4, order.getRemark());
            ps.setString(5, order.getStatus().name());
            ps.setString(6, order.getCreatedBy());
            ps.setObject(7, toDb(order.getCreatedAt()));
            ps.setString(8, order.getUpdatedBy());
            ps.setObject(9, toDb(order.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得采购订单头自增主键").longValue();

        for (PurchaseOrderLine line : order.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, PurchaseOrderLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO purchase_order_line (purchase_order_id, line_no, product_id, "
                            + "quantity, unit_price, amount, received_qty) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setLong(3, line.getProductId());
            ps.setBigDecimal(4, line.getQuantity());
            ps.setBigDecimal(5, line.getUnitPrice());
            ps.setBigDecimal(6, line.getAmount());
            ps.setBigDecimal(7, line.getReceivedQty());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得采购订单行自增主键").longValue());
    }

    private void update(long headId, PurchaseOrder order) {
        jdbc.update("UPDATE purchase_order SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                order.getStatus().name(), order.getReversalOfId(), order.getReversedById(),
                order.getUpdatedBy(), toDb(order.getUpdatedAt()), headId);
        // 行内容（数量/单价/金额）建单后不变；唯一可变的是 received_qty（收货过账累加），逐行回写
        for (PurchaseOrderLine line : order.getLines()) {
            jdbc.update("UPDATE purchase_order_line SET received_qty = ? "
                            + "WHERE purchase_order_id = ? AND line_no = ?",
                    line.getReceivedQty(), headId, line.getLineNo());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseOrder> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PurchaseOrder> search(PurchaseOrderQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.supplierId() != null) {
            where.append("AND supplier_id = ? ");
            args.add(query.supplierId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }
        if (query.receivableOnly()) {
            where.append("AND EXISTS (SELECT 1 FROM purchase_order_line pol "
                    + "WHERE pol.tenant_id = purchase_order.tenant_id "
                    + "AND pol.purchase_order_id = purchase_order.id "
                    + "AND pol.quantity > pol.received_qty) ");
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order " + where,
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

        List<PurchaseOrder> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    private PurchaseOrder toDocument(HeadRow head) {
        List<PurchaseOrderLine> lines = loadLines(head.id());
        return PurchaseOrder.restore(head.docNo(), head.supplierId(), head.orderDate(),
                head.remark(), head.status(), lines, head.createdBy());
    }

    private List<PurchaseOrderLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, product_id, quantity, unit_price, amount, received_qty "
                        + "FROM purchase_order_line WHERE purchase_order_id = ? ORDER BY line_no",
                (rs, rowNum) -> PurchaseOrderLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("received_qty")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM purchase_order WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, long supplierId, LocalDate orderDate,
                           String remark, DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getLong("supplier_id"),
            rs.getObject("order_date", LocalDate.class),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
