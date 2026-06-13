package com.sjherp.infra.persistence.collection;

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

import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.collection.CollectionReceiptRepository;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;

/**
 * 收款单仓储的 MySQL 实现（M4-T04b；代码风格照 {@code JdbcPurchaseInvoiceRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段
 * （行集合建单后不增删、内容不变，更新时不触碰行表）。tenant_id v1.0 恒 0（ADR-002）；
 * 时间列 DATETIME(6) 按 UTC 读写。
 */
@Transactional
public class JdbcCollectionReceiptRepository implements CollectionReceiptRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, customer_id, payment_account_id, receipt_date, remark, status, "
                    + "created_by FROM collection_receipt ";

    private final JdbcTemplate jdbc;

    public JdbcCollectionReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(CollectionReceipt receipt) {
        Long headId = findHeadId(receipt.getDocNo());
        if (headId == null) {
            insert(receipt);
        } else {
            update(headId, receipt);
        }
    }

    private void insert(CollectionReceipt receipt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO collection_receipt (doc_no, customer_id, payment_account_id, "
                            + "receipt_date, remark, status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, receipt.getDocNo());
            ps.setLong(2, receipt.getCustomerId());
            ps.setLong(3, receipt.getPaymentAccountId());
            ps.setObject(4, receipt.getReceiptDate());
            ps.setString(5, receipt.getRemark());
            ps.setString(6, receipt.getStatus().name());
            ps.setString(7, receipt.getCreatedBy());
            ps.setObject(8, toDb(receipt.getCreatedAt()));
            ps.setString(9, receipt.getUpdatedBy());
            ps.setObject(10, toDb(receipt.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得收款单头自增主键").longValue();

        for (CollectionReceiptLine line : receipt.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, CollectionReceiptLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO collection_receipt_line (collection_receipt_id, line_no, "
                            + "receivable_id, allocated_amount) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setLong(3, line.getReceivableId());
            ps.setBigDecimal(4, line.getAllocatedAmount());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得收款单行自增主键").longValue());
    }

    private void update(long headId, CollectionReceipt receipt) {
        jdbc.update("UPDATE collection_receipt SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                receipt.getStatus().name(), receipt.getReversalOfId(), receipt.getReversedById(),
                receipt.getUpdatedBy(), toDb(receipt.getUpdatedAt()), headId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CollectionReceipt> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<CollectionReceipt> search(CollectionReceiptQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.customerId() != null) {
            where.append("AND customer_id = ? ");
            args.add(query.customerId());
        }
        if (query.paymentAccountId() != null) {
            where.append("AND payment_account_id = ? ");
            args.add(query.paymentAccountId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM collection_receipt " + where,
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

        List<CollectionReceipt> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    private CollectionReceipt toDocument(HeadRow head) {
        List<CollectionReceiptLine> lines = loadLines(head.id());
        return CollectionReceipt.restore(head.docNo(), head.customerId(), head.paymentAccountId(),
                head.receiptDate(), head.remark(), head.status(), lines, head.createdBy());
    }

    private List<CollectionReceiptLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, receivable_id, allocated_amount "
                        + "FROM collection_receipt_line WHERE collection_receipt_id = ? ORDER BY line_no",
                (rs, rowNum) -> CollectionReceiptLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getLong("receivable_id"),
                        rs.getBigDecimal("allocated_amount")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM collection_receipt WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, long customerId, long paymentAccountId,
                           LocalDate receiptDate, String remark, DocumentStatus status,
                           String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getLong("customer_id"),
            rs.getLong("payment_account_id"),
            rs.getObject("receipt_date", LocalDate.class),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
