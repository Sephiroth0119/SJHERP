package com.sjherp.infra.persistence.gap;

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

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordQuery;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gap.GapStatus;

/**
 * 流程缺口仓储的 MySQL 实现（V5 迁移 gap_record 表）。
 *
 * <p>时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcProductRepository）。
 * 更新只触碰可变部分（status / updated_by / updated_at）——缺口内容
 * 落库后不可修改（误报走 REJECTED 终态，可审计）。
 */
@Transactional
public class JdbcGapRecordRepository implements GapRecordRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, gap_no, session_id, title, scenario, expected_behavior, missing_capability, "
                    + "business_module, severity, status, reporter, "
                    + "created_by, created_at, updated_by, updated_at FROM gap_record ";

    private static final RowMapper<GapRecord> ROW_MAPPER = (rs, rowNum) -> GapRecord.restore(
            rs.getLong("id"),
            rs.getString("gap_no"),
            rs.getString("session_id"),
            rs.getString("title"),
            rs.getString("scenario"),
            rs.getString("expected_behavior"),
            rs.getString("missing_capability"),
            BusinessModule.valueOf(rs.getString("business_module")),
            GapSeverity.valueOf(rs.getString("severity")),
            GapStatus.valueOf(rs.getString("status")),
            rs.getString("reporter"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcGapRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(GapRecord record) {
        if (record.getId() == null) {
            insert(record);
        } else {
            update(record);
        }
    }

    private void insert(GapRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO gap_record (gap_no, session_id, title, scenario, expected_behavior, "
                            + "missing_capability, business_module, severity, status, reporter, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.getGapNo());
            ps.setString(2, record.getSessionId());
            ps.setString(3, record.getTitle());
            ps.setString(4, record.getScenario());
            ps.setString(5, record.getExpectedBehavior());
            ps.setString(6, record.getMissingCapability());
            ps.setString(7, record.getBusinessModule().name());
            ps.setString(8, record.getSeverity().name());
            ps.setString(9, record.getStatus().name());
            ps.setString(10, record.getReporter());
            ps.setString(11, record.getCreatedBy());
            ps.setObject(12, toDb(record.getCreatedAt()));
            ps.setString(13, record.getUpdatedBy());
            ps.setObject(14, toDb(record.getUpdatedAt()));
            return ps;
        }, keyHolder);
        record.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    private void update(GapRecord record) {
        // 缺口内容（场景/期望/缺失能力等）落库后不可修改，只更新状态与审计尾巴
        jdbc.update("UPDATE gap_record SET status = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                record.getStatus().name(), record.getUpdatedBy(), toDb(record.getUpdatedAt()),
                record.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GapRecord> findById(long id) {
        List<GapRecord> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<GapRecord> search(GapRecordQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }
        if (query.module() != null) {
            where.append("AND business_module = ? ");
            args.add(query.module().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM gap_record " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<GapRecord> rows = jdbc.query(SELECT_COLUMNS + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, totalCount, query.page(), query.size());
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
