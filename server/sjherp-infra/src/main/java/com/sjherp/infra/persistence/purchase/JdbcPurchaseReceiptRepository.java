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
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLine;
import com.sjherp.domain.purchase.PurchaseReceiptQuery;
import com.sjherp.domain.purchase.PurchaseReceiptRepository;

/**
 * 采购入库单仓储的 MySQL 实现（M3-T06；代码风格照 {@code JdbcTransferRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段，
 * <b>并逐行更新 invoiced_qty</b>（累计已开票量由采购发票过账回写，行集合建单后不增删）。
 * tenant_id v1.0 恒 0（ADR-002）；时间列 DATETIME(6) 按 UTC 读写。
 */
@Transactional
public class JdbcPurchaseReceiptRepository implements PurchaseReceiptRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, purchase_order_no, warehouse_id, receipt_date, remark, status, created_by "
                    + "FROM purchase_receipt ";

    private final JdbcTemplate jdbc;

    public JdbcPurchaseReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PurchaseReceipt receipt) {
        Long headId = findHeadId(receipt.getDocNo());
        if (headId == null) {
            insert(receipt);
        } else {
            update(headId, receipt);
        }
    }

    private void insert(PurchaseReceipt receipt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO purchase_receipt (doc_no, purchase_order_no, warehouse_id, "
                            + "receipt_date, remark, status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, receipt.getDocNo());
            ps.setString(2, receipt.getPurchaseOrderNo());
            ps.setLong(3, receipt.getWarehouseId());
            ps.setObject(4, receipt.getReceiptDate());
            ps.setString(5, receipt.getRemark());
            ps.setString(6, receipt.getStatus().name());
            ps.setString(7, receipt.getCreatedBy());
            ps.setObject(8, toDb(receipt.getCreatedAt()));
            ps.setString(9, receipt.getUpdatedBy());
            ps.setObject(10, toDb(receipt.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得采购入库单头自增主键").longValue();

        for (PurchaseReceiptLine line : receipt.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, PurchaseReceiptLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO purchase_receipt_line (purchase_receipt_id, line_no, po_line_no, "
                            + "product_id, quantity, unit_cost, amount, invoiced_qty) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setInt(3, line.getPoLineNo());
            ps.setLong(4, line.getProductId());
            ps.setBigDecimal(5, line.getQuantity());
            ps.setBigDecimal(6, line.getUnitCost());
            ps.setBigDecimal(7, line.getAmount());
            ps.setBigDecimal(8, line.getInvoicedQty());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得采购入库单行自增主键").longValue());
    }

    private void update(long headId, PurchaseReceipt receipt) {
        jdbc.update("UPDATE purchase_receipt SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                receipt.getStatus().name(), receipt.getReversalOfId(), receipt.getReversedById(),
                receipt.getUpdatedBy(), toDb(receipt.getUpdatedAt()), headId);
        // invoiced_qty 由采购发票过账回写，更新时逐行落库（行集合建单后不增删，仅累计开票量变化）
        for (PurchaseReceiptLine line : receipt.getLines()) {
            jdbc.update("UPDATE purchase_receipt_line SET invoiced_qty = ? "
                            + "WHERE purchase_receipt_id = ? AND line_no = ?",
                    line.getInvoicedQty(), headId, line.getLineNo());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseReceipt> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PurchaseReceipt> search(PurchaseReceiptQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.warehouseId() != null) {
            where.append("AND warehouse_id = ? ");
            args.add(query.warehouseId());
        }
        if (query.purchaseOrderNo() != null) {
            where.append("AND purchase_order_no = ? ");
            args.add(query.purchaseOrderNo());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }
        if (query.invoiceableOnly()) {
            where.append("AND status = 'COMPLETED' ")
                    .append("AND EXISTS (SELECT 1 FROM purchase_receipt_line invoiceable_line ")
                    .append("WHERE invoiceable_line.tenant_id = purchase_receipt.tenant_id ")
                    .append("AND invoiceable_line.purchase_receipt_id = purchase_receipt.id ")
                    .append("AND invoiceable_line.quantity > invoiceable_line.invoiced_qty) ");
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_receipt " + where,
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

        List<PurchaseReceipt> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    private PurchaseReceipt toDocument(HeadRow head) {
        List<PurchaseReceiptLine> lines = loadLines(head.id());
        return PurchaseReceipt.restore(head.docNo(), head.purchaseOrderNo(), head.warehouseId(),
                head.receiptDate(), head.remark(), head.status(), lines, head.createdBy());
    }

    private List<PurchaseReceiptLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, po_line_no, product_id, quantity, unit_cost, amount, "
                        + "invoiced_qty FROM purchase_receipt_line WHERE purchase_receipt_id = ? ORDER BY line_no",
                (rs, rowNum) -> PurchaseReceiptLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getInt("po_line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("invoiced_qty")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM purchase_receipt WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, String purchaseOrderNo, long warehouseId,
                           LocalDate receiptDate, String remark, DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getString("purchase_order_no"),
            rs.getLong("warehouse_id"),
            rs.getObject("receipt_date", LocalDate.class),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
