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
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceLine;
import com.sjherp.domain.purchase.PurchaseInvoiceQuery;
import com.sjherp.domain.purchase.PurchaseInvoiceRepository;

/**
 * 采购发票仓储的 MySQL 实现（M3-T07；代码风格照 {@code JdbcTransferRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段
 * （行集合建单后不增删、内容不变，更新时不触碰行表）。tenant_id v1.0 恒 0（ADR-002）；
 * 时间列 DATETIME(6) 按 UTC 读写。
 */
@Transactional
public class JdbcPurchaseInvoiceRepository implements PurchaseInvoiceRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, purchase_receipt_no, supplier_id, invoice_date, supplier_invoice_no, "
                    + "remark, status, created_by FROM purchase_invoice ";

    private final JdbcTemplate jdbc;

    public JdbcPurchaseInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PurchaseInvoice invoice) {
        Long headId = findHeadId(invoice.getDocNo());
        if (headId == null) {
            insert(invoice);
        } else {
            update(headId, invoice);
        }
    }

    private void insert(PurchaseInvoice invoice) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO purchase_invoice (doc_no, purchase_receipt_no, supplier_id, "
                            + "invoice_date, supplier_invoice_no, remark, status, created_by, "
                            + "created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, invoice.getDocNo());
            ps.setString(2, invoice.getPurchaseReceiptNo());
            ps.setLong(3, invoice.getSupplierId());
            ps.setObject(4, invoice.getInvoiceDate());
            ps.setString(5, invoice.getSupplierInvoiceNo());
            ps.setString(6, invoice.getRemark());
            ps.setString(7, invoice.getStatus().name());
            ps.setString(8, invoice.getCreatedBy());
            ps.setObject(9, toDb(invoice.getCreatedAt()));
            ps.setString(10, invoice.getUpdatedBy());
            ps.setObject(11, toDb(invoice.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得采购发票头自增主键").longValue();

        for (PurchaseInvoiceLine line : invoice.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, PurchaseInvoiceLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO purchase_invoice_line (purchase_invoice_id, line_no, receipt_line_no, "
                            + "product_id, quantity, amount) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setInt(3, line.getReceiptLineNo());
            ps.setLong(4, line.getProductId());
            ps.setBigDecimal(5, line.getQuantity());
            ps.setBigDecimal(6, line.getAmount());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得采购发票行自增主键").longValue());
    }

    private void update(long headId, PurchaseInvoice invoice) {
        jdbc.update("UPDATE purchase_invoice SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                invoice.getStatus().name(), invoice.getReversalOfId(), invoice.getReversedById(),
                invoice.getUpdatedBy(), toDb(invoice.getUpdatedAt()), headId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseInvoice> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PurchaseInvoice> search(PurchaseInvoiceQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.supplierId() != null) {
            where.append("AND supplier_id = ? ");
            args.add(query.supplierId());
        }
        if (query.purchaseReceiptNo() != null) {
            where.append("AND purchase_receipt_no = ? ");
            args.add(query.purchaseReceiptNo());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_invoice " + where,
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

        List<PurchaseInvoice> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    private PurchaseInvoice toDocument(HeadRow head) {
        List<PurchaseInvoiceLine> lines = loadLines(head.id());
        return PurchaseInvoice.restore(head.docNo(), head.purchaseReceiptNo(), head.supplierId(),
                head.invoiceDate(), head.supplierInvoiceNo(), head.remark(), head.status(),
                lines, head.createdBy());
    }

    private List<PurchaseInvoiceLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, receipt_line_no, product_id, quantity, amount "
                        + "FROM purchase_invoice_line WHERE purchase_invoice_id = ? ORDER BY line_no",
                (rs, rowNum) -> PurchaseInvoiceLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getInt("receipt_line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("amount")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM purchase_invoice WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, String purchaseReceiptNo, long supplierId,
                           LocalDate invoiceDate, String supplierInvoiceNo, String remark,
                           DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getString("purchase_receipt_no"),
            rs.getLong("supplier_id"),
            rs.getObject("invoice_date", LocalDate.class),
            rs.getString("supplier_invoice_no"),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
