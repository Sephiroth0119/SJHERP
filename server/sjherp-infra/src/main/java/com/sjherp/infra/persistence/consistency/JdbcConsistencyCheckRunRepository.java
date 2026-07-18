package com.sjherp.infra.persistence.consistency;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;
import com.sjherp.domain.consistency.ConsistencyFinding;
import com.sjherp.domain.consistency.ConsistencyRunQuery;

/** MySQL implementation of append-only consistency-check run reports. */
@Transactional
public final class JdbcConsistencyCheckRunRepository implements ConsistencyCheckRunRepository {

    private static final String HEAD_COLUMNS = """
            SELECT id, tenant_id, run_no, trigger_type, requested_by, started_at, completed_at,
                   status, clean, total_count, error_count, warn_count, info_count,
                   analysis_status, analysis_summary, failure_type, created_at
              FROM consistency_check_run
            """;

    private static final RowMapper<Head> HEAD_ROW_MAPPER = (rs, rowNum) -> new Head(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("run_no"),
            ConsistencyCheckRun.TriggerType.valueOf(rs.getString("trigger_type")),
            rs.getString("requested_by"), fromDb(rs.getObject("started_at", LocalDateTime.class)),
            fromDb(rs.getObject("completed_at", LocalDateTime.class)),
            ConsistencyCheckRun.Status.valueOf(rs.getString("status")), rs.getBoolean("clean"),
            rs.getLong("total_count"), rs.getLong("error_count"), rs.getLong("warn_count"),
            rs.getLong("info_count"),
            ConsistencyCheckRun.AnalysisStatus.valueOf(rs.getString("analysis_status")),
            rs.getString("analysis_summary"), rs.getString("failure_type"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<ConsistencyFinding> FINDING_ROW_MAPPER = (rs, rowNum) ->
            new ConsistencyFinding(rs.getInt("sequence_no"), rs.getString("rule_code"),
                    rs.getString("check_type"), rs.getString("object_key"),
                    rs.getBigDecimal("expected_value"), rs.getBigDecimal("actual_value"),
                    ConsistencyFinding.Severity.valueOf(rs.getString("severity")), rs.getString("message"));

    private final JdbcTemplate jdbc;

    public JdbcConsistencyCheckRunRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void save(ConsistencyCheckRun run) {
        Objects.requireNonNull(run, "run must not be null");
        if (run.id() != null) {
            throw new IllegalArgumentException("运行报告不可重复保存");
        }
        long id = insertHead(run);
        if (!run.findings().isEmpty()) {
            insertFindings(run, id);
        }
        run.assignId(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConsistencyCheckRun> findByRunNo(long tenantId, String runNo) {
        return findHead(tenantId, runNo).map(head -> restore(head, findFindings(tenantId, head.id())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ConsistencyCheckRun> search(long tenantId, ConsistencyRunQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        long total = countHeads(tenantId);
        long offset = Math.multiplyExact((long) (query.page() - 1), query.size());
        List<ConsistencyCheckRun> heads = findHeads(tenantId, query.size(),
                offset).stream().map(head -> restore(head, List.of())).toList();
        return new PageResult<>(heads, total, query.page(), query.size());
    }

    private long insertHead(ConsistencyCheckRun run) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO consistency_check_run (
                        tenant_id, run_no, trigger_type, requested_by, started_at, completed_at,
                        status, clean, total_count, error_count, warn_count, info_count,
                        analysis_status, analysis_summary, failure_type, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            statement.setLong(index++, run.tenantId());
            statement.setString(index++, run.runNo());
            statement.setString(index++, run.triggerType().name());
            statement.setString(index++, run.requestedBy());
            statement.setObject(index++, toDb(run.startedAt()));
            statement.setObject(index++, toDb(run.completedAt()));
            statement.setString(index++, run.status().name());
            statement.setBoolean(index++, run.clean());
            statement.setLong(index++, run.totalCount());
            statement.setLong(index++, run.errorCount());
            statement.setLong(index++, run.warnCount());
            statement.setLong(index++, run.infoCount());
            statement.setString(index++, run.analysisStatus().name());
            statement.setString(index++, run.analysisSummary());
            statement.setString(index++, run.failureType());
            statement.setObject(index, toDb(run.createdAt()));
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "未取得运行报告自增主键").longValue();
    }

    private void insertFindings(ConsistencyCheckRun run, long runId) {
        jdbc.batchUpdate("""
                INSERT INTO consistency_check_break (
                    tenant_id, run_id, sequence_no, rule_code, check_type, object_key,
                    expected_value, actual_value, severity, message, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                ConsistencyFinding finding = run.findings().get(index);
                int parameter = 1;
                statement.setLong(parameter++, run.tenantId());
                statement.setLong(parameter++, runId);
                statement.setInt(parameter++, finding.sequenceNo());
                statement.setString(parameter++, finding.ruleCode());
                statement.setString(parameter++, finding.checkType());
                statement.setString(parameter++, finding.objectKey());
                statement.setBigDecimal(parameter++, finding.expectedValue());
                statement.setBigDecimal(parameter++, finding.actualValue());
                statement.setString(parameter++, finding.severity().name());
                statement.setString(parameter++, finding.message());
                statement.setObject(parameter, toDb(run.createdAt()));
            }

            @Override
            public int getBatchSize() {
                return run.findings().size();
            }
        });
    }

    private Optional<Head> findHead(long tenantId, String runNo) {
        return first(jdbc.query(HEAD_COLUMNS + """
                WHERE tenant_id = ? AND run_no = ?
                """, HEAD_ROW_MAPPER, tenantId, runNo));
    }

    private List<ConsistencyFinding> findFindings(long tenantId, long runId) {
        return jdbc.query("""
                SELECT sequence_no, rule_code, check_type, object_key, expected_value, actual_value,
                       severity, message
                  FROM consistency_check_break
                 WHERE tenant_id = ? AND run_id = ?
                 ORDER BY sequence_no
                """, FINDING_ROW_MAPPER, tenantId, runId);
    }

    private long countHeads(long tenantId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consistency_check_run WHERE tenant_id = ?", Long.class, tenantId);
        return total == null ? 0L : total;
    }

    private List<Head> findHeads(long tenantId, int size, long offset) {
        return jdbc.query(HEAD_COLUMNS + """
                WHERE tenant_id = ?
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, HEAD_ROW_MAPPER, tenantId, size, offset);
    }

    private static ConsistencyCheckRun restore(Head head, List<ConsistencyFinding> findings) {
        return ConsistencyCheckRun.restore(head.id(), head.tenantId(), head.runNo(), head.triggerType(),
                head.requestedBy(), head.startedAt(), head.completedAt(), head.status(), head.clean(),
                head.totalCount(), head.errorCount(), head.warnCount(), head.infoCount(),
                head.analysisStatus(), head.analysisSummary(), head.failureType(), head.createdAt(), findings);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime value) {
        return Objects.requireNonNull(value, "database timestamp must not be null").toInstant(ZoneOffset.UTC);
    }

    private record Head(long id, long tenantId, String runNo, ConsistencyCheckRun.TriggerType triggerType,
                        String requestedBy, Instant startedAt, Instant completedAt,
                        ConsistencyCheckRun.Status status, boolean clean, long totalCount,
                        long errorCount, long warnCount, long infoCount,
                        ConsistencyCheckRun.AnalysisStatus analysisStatus, String analysisSummary,
                        String failureType, Instant createdAt) {
    }
}
