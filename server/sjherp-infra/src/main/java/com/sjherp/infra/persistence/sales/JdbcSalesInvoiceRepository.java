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
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceLine;
import com.sjherp.domain.sales.SalesInvoiceQuery;
import com.sjherp.domain.sales.SalesInvoiceRepository;

/**
 * 销售发票仓储的 MySQL 实现（M3-T10，代码风格照 {@code JdbcTransferRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段
 * （行内容建单后不变，更新时不触碰行表）。
 *
 * <p>tenant_id v1.0 恒 0（ADR-002）。时间列 DATETIME(6) 一律按 UTC 读写。
 */
@Transactional
public class JdbcSalesInvoiceRepository implements SalesInvoiceRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, sales_delivery_no, customer_id, invoice_date, due_date, remark, "
                    + "status, created_by FROM sales_invoice ";

    private final JdbcTemplate jdbc;

    public JdbcSalesInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(SalesInvoice invoice) {
        Long headId = findHeadId(invoice.getDocNo());
        if (headId == null) {
            insert(invoice);
        } else {
            update(headId, invoice);
        }
    }

    private void insert(SalesInvoice invoice) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sales_invoice (doc_no, sales_delivery_no, customer_id, invoice_date, "
                            + "due_date, remark, status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, invoice.getDocNo());
            ps.setString(2, invoice.getSalesDeliveryNo());
            ps.setLong(3, invoice.getCustomerId());
            ps.setObject(4, invoice.getInvoiceDate());
            ps.setObject(5, invoice.getDueDate());
            ps.setString(6, invoice.getRemark());
            ps.setString(7, invoice.getStatus().name());
            ps.setString(8, invoice.getCreatedBy());
            ps.setObject(9, toDb(invoice.getCreatedAt()));
            ps.setString(10, invoice.getUpdatedBy());
            ps.setObject(11, toDb(invoice.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得发票头自增主键").longValue();

        for (SalesInvoiceLine line : invoice.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, SalesInvoiceLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sales_invoice_line (sales_invoice_id, line_no, delivery_line_no, "
                            + "product_id, quantity, unit_price, amount) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setInt(3, line.getDeliveryLineNo());
            ps.setLong(4, line.getProductId());
            ps.setBigDecimal(5, line.getQuantity());
            ps.setBigDecimal(6, line.getUnitPrice());
            ps.setBigDecimal(7, line.getAmount());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得发票行自增主键").longValue());
    }

    private void update(long headId, SalesInvoice invoice) {
        jdbc.update("UPDATE sales_invoice SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                invoice.getStatus().name(), invoice.getReversalOfId(), invoice.getReversedById(),
                invoice.getUpdatedBy(), toDb(invoice.getUpdatedAt()), headId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesInvoice> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toInvoice(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SalesInvoice> search(SalesInvoiceQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.customerId() != null) {
            where.append("AND customer_id = ? ");
            args.add(query.customerId());
        }
        if (query.salesDeliveryNo() != null) {
            where.append("AND sales_delivery_no = ? ");
            args.add(query.salesDeliveryNo());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice " + where,
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

        List<SalesInvoice> invoices = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            invoices.add(toInvoice(head));
        }
        return new PageResult<>(invoices, totalCount, query.page(), query.size());
    }

    private SalesInvoice toInvoice(HeadRow head) {
        List<SalesInvoiceLine> lines = loadLines(head.id());
        return SalesInvoice.restore(head.docNo(), head.salesDeliveryNo(), head.customerId(),
                head.invoiceDate(), head.dueDate(), head.remark(), head.status(), lines, head.createdBy());
    }

    private List<SalesInvoiceLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, delivery_line_no, product_id, quantity, unit_price, amount "
                        + "FROM sales_invoice_line WHERE sales_invoice_id = ? ORDER BY line_no",
                (rs, rowNum) -> SalesInvoiceLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getInt("delivery_line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("amount")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM sales_invoice WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private record HeadRow(long id, String docNo, String salesDeliveryNo, long customerId,
                           LocalDate invoiceDate, LocalDate dueDate, String remark,
                           DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getString("sales_delivery_no"),
            rs.getLong("customer_id"),
            rs.getObject("invoice_date", LocalDate.class),
            rs.getObject("due_date", LocalDate.class),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
