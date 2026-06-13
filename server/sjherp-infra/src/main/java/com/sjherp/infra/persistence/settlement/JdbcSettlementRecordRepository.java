package com.sjherp.infra.persistence.settlement;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementRecordRepository;
import com.sjherp.domain.settlement.SettlementType;

/**
 * 核销记录仓储的 MySQL 实现（M4-T03，代码风格照 {@code JdbcReceivableRepository}）。
 *
 * <p>核销记录只追加（settle 只产生新记录，记录本身永不更新/删除，CLAUDE.md 原则 2/3）：
 * 仅 INSERT + 回填自增 id 与只读查询。tenant_id v1.0 恒 0（ADR-002）；
 * created_at DATETIME(6) 按 UTC 读写；金额 DECIMAL。
 */
@Transactional
public class JdbcSettlementRecordRepository implements SettlementRecordRepository {

    private static final String SELECT_ALL =
            "SELECT id, settlement_type, target_id, target_source_doc_no, amount, settlement_date, "
                    + "payment_doc_no, created_by, created_at FROM settlement_record ";

    private final JdbcTemplate jdbc;

    public JdbcSettlementRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    @Override
    public void save(SettlementRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement_record (settlement_type, target_id, target_source_doc_no, "
                            + "amount, settlement_date, payment_doc_no, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.getType().name());
            ps.setLong(2, record.getTargetId());
            ps.setString(3, record.getTargetSourceDocNo());
            ps.setBigDecimal(4, record.getAmount());
            ps.setObject(5, record.getSettlementDate());
            ps.setString(6, record.getPaymentDocNo());
            ps.setString(7, record.getCreatedBy());
            ps.setObject(8, toDb(record.getCreatedAt()));
            return ps;
        }, keyHolder);
        record.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得核销记录自增主键").longValue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementRecord> findByTarget(SettlementType type, long targetId) {
        return jdbc.query(
                SELECT_ALL + "WHERE tenant_id = 0 AND settlement_type = ? AND target_id = ? ORDER BY id",
                ROW_MAPPER, type.name(), targetId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementRecord> findByPaymentDocNo(String paymentDocNo) {
        return jdbc.query(
                SELECT_ALL + "WHERE tenant_id = 0 AND payment_doc_no = ? ORDER BY id",
                ROW_MAPPER, paymentDocNo);
    }

    private static final RowMapper<SettlementRecord> ROW_MAPPER = (rs, rowNum) -> SettlementRecord.restore(
            rs.getLong("id"),
            SettlementType.valueOf(rs.getString("settlement_type")),
            rs.getLong("target_id"),
            rs.getString("target_source_doc_no"),
            rs.getBigDecimal("amount"),
            rs.getObject("settlement_date", LocalDate.class),
            rs.getString("payment_doc_no"),
            rs.getString("created_by"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
