package com.sjherp.infra.persistence.payment;

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
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementLine;
import com.sjherp.domain.payment.PaymentDisbursementQuery;
import com.sjherp.domain.payment.PaymentDisbursementRepository;

/**
 * 付款单仓储的 MySQL 实现（M4-T04b；代码风格照 {@code JdbcCollectionReceiptRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段
 * （行集合建单后不增删、内容不变，更新时不触碰行表）。tenant_id v1.0 恒 0（ADR-002）；
 * 时间列 DATETIME(6) 按 UTC 读写。
 */
@Transactional
public class JdbcPaymentDisbursementRepository implements PaymentDisbursementRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, supplier_id, payment_account_id, payment_date, remark, status, "
                    + "created_by FROM payment_disbursement ";

    private final JdbcTemplate jdbc;

    public JdbcPaymentDisbursementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PaymentDisbursement disbursement) {
        Long headId = findHeadId(disbursement.getDocNo());
        if (headId == null) {
            insert(disbursement);
        } else {
            update(headId, disbursement);
        }
    }

    private void insert(PaymentDisbursement disbursement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO payment_disbursement (doc_no, supplier_id, payment_account_id, "
                            + "payment_date, remark, status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, disbursement.getDocNo());
            ps.setLong(2, disbursement.getSupplierId());
            ps.setLong(3, disbursement.getPaymentAccountId());
            ps.setObject(4, disbursement.getPaymentDate());
            ps.setString(5, disbursement.getRemark());
            ps.setString(6, disbursement.getStatus().name());
            ps.setString(7, disbursement.getCreatedBy());
            ps.setObject(8, toDb(disbursement.getCreatedAt()));
            ps.setString(9, disbursement.getUpdatedBy());
            ps.setObject(10, toDb(disbursement.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得付款单头自增主键").longValue();

        for (PaymentDisbursementLine line : disbursement.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, PaymentDisbursementLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO payment_disbursement_line (payment_disbursement_id, line_no, "
                            + "payable_id, allocated_amount) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setLong(3, line.getPayableId());
            ps.setBigDecimal(4, line.getAllocatedAmount());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得付款单行自增主键").longValue());
    }

    private void update(long headId, PaymentDisbursement disbursement) {
        jdbc.update("UPDATE payment_disbursement SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                disbursement.getStatus().name(), disbursement.getReversalOfId(),
                disbursement.getReversedById(), disbursement.getUpdatedBy(),
                toDb(disbursement.getUpdatedAt()), headId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentDisbursement> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PaymentDisbursement> search(PaymentDisbursementQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.supplierId() != null) {
            where.append("AND supplier_id = ? ");
            args.add(query.supplierId());
        }
        if (query.paymentAccountId() != null) {
            where.append("AND payment_account_id = ? ");
            args.add(query.paymentAccountId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM payment_disbursement " + where,
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

        List<PaymentDisbursement> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    private PaymentDisbursement toDocument(HeadRow head) {
        List<PaymentDisbursementLine> lines = loadLines(head.id());
        return PaymentDisbursement.restore(head.docNo(), head.supplierId(), head.paymentAccountId(),
                head.paymentDate(), head.remark(), head.status(), lines, head.createdBy());
    }

    private List<PaymentDisbursementLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, payable_id, allocated_amount "
                        + "FROM payment_disbursement_line WHERE payment_disbursement_id = ? ORDER BY line_no",
                (rs, rowNum) -> PaymentDisbursementLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getLong("payable_id"),
                        rs.getBigDecimal("allocated_amount")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM payment_disbursement WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, long supplierId, long paymentAccountId,
                           LocalDate paymentDate, String remark, DocumentStatus status,
                           String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getLong("supplier_id"),
            rs.getLong("payment_account_id"),
            rs.getObject("payment_date", LocalDate.class),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
