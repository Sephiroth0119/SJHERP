package com.sjherp.infra.persistence.gap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.GapIssueStatus;
import com.sjherp.domain.gap.GapSeverity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class JdbcGapIssueCandidateRepository implements GapIssueCandidateRepository {
    private static final int TENANT_ID = 0;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcGapIssueCandidateRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public GapIssueCandidate upsert(GapIssueCandidate candidate) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String sql = "INSERT INTO gap_issue_candidate(tenant_id,idempotency_key,cluster_key,business_module,"
                + "severity,title,scenario_samples,expected_behavior,missing_capability,status,created_at,updated_at) "
                + "VALUES(0,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)";
        jdbc.update(sql,
                candidate.idempotencyKey(),
                candidate.clusterKey(),
                candidate.businessModule().name(),
                candidate.severity().name(),
                candidate.title(),
                write(candidate.scenarioSamples()),
                candidate.expectedBehavior(),
                candidate.missingCapability(),
                candidate.status().name(),
                now,
                now);

        GapIssueCandidate current = findByKeyForUpdate(candidate.idempotencyKey()).orElseThrow();
        if (canMergeSamples(current.status())) {
            LinkedHashSet<String> samples = new LinkedHashSet<>(current.scenarioSamples());
            samples.addAll(candidate.scenarioSamples());
            List<String> merged = samples.stream().limit(20).toList();
            jdbc.update("UPDATE gap_issue_candidate SET scenario_samples=?,updated_at=? "
                            + "WHERE tenant_id=0 AND id=? AND status IN ('PENDING','APPROVED','FAILED')",
                    write(merged),
                    now,
                    current.id());
        }
        return findById(current.id()).orElseThrow();
    }

    @Override
    public void addSources(long candidateId, List<String> gapNos) {
        for (String gapNo : gapNos) {
            jdbc.update("INSERT INTO gap_issue_source(tenant_id,candidate_id,gap_no,created_at) VALUES(0,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE candidate_id=candidate_id",
                    candidateId,
                    gapNo,
                    LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    @Override
    public List<GapIssueCandidate> findAll() {
        return jdbc.query("SELECT * FROM gap_issue_candidate WHERE tenant_id=0 ORDER BY id",
                (rs, rowNum) -> map(rs));
    }

    @Override
    public List<GapIssueCandidate> findDispatchable(int maxAttempts, int limit) {
        return jdbc.query("SELECT * FROM gap_issue_candidate WHERE tenant_id=0 "
                        + "AND status IN ('PENDING','APPROVED','FAILED') AND attempt_count < ? ORDER BY id LIMIT ?",
                (rs, rowNum) -> map(rs),
                maxAttempts,
                limit);
    }

    @Override
    public Optional<GapIssueCandidate> findById(long id) {
        return jdbc.query("SELECT * FROM gap_issue_candidate WHERE tenant_id=0 AND id=?",
                        (rs, rowNum) -> map(rs),
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> claimForSend(long id) {
        String token = java.util.UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int changed = jdbc.update("UPDATE gap_issue_candidate SET status='SENDING',attempt_count=attempt_count+1,"
                        + "sending_started_at=?,lease_token=?,updated_at=? WHERE tenant_id=0 AND id=? "
                        + "AND status IN ('APPROVED','FAILED') AND issue_number IS NULL",
                now,
                token,
                now,
                id);
        return changed == 1 ? Optional.of(token) : Optional.empty();
    }

    @Override
    public int reclaimExpiredSending(Instant cutoff) {
        return jdbc.update("UPDATE gap_issue_candidate SET status='FAILED',failure_type='LEASE_EXPIRED',"
                        + "sending_started_at=NULL,lease_token=NULL,updated_at=? WHERE tenant_id=0 "
                        + "AND status='SENDING' AND sending_started_at<?",
                LocalDateTime.now(ZoneOffset.UTC),
                LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }

    @Override
    public void markApproved(long id, String operator) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int changed = jdbc.update("UPDATE gap_issue_candidate SET status='APPROVED',reviewed_by=?,reviewed_at=?,updated_at=? "
                        + "WHERE tenant_id=0 AND id=? AND status IN ('PENDING','FAILED')",
                operator,
                now,
                now,
                id);
        if (changed != 1) {
            throw new IllegalStateException("candidate cannot be approved in its current state");
        }
    }

    @Override
    public void markSent(long id, String leaseToken, long number, String url) {
        int changed = jdbc.update("UPDATE gap_issue_candidate SET status='SENT',issue_number=?,issue_url=?,"
                        + "failure_type=NULL,sending_started_at=NULL,lease_token=NULL,updated_at=? "
                        + "WHERE tenant_id=0 AND id=? AND status='SENDING' AND lease_token=?",
                number,
                url,
                LocalDateTime.now(ZoneOffset.UTC),
                id,
                leaseToken);
        if (changed != 1) {
            throw new IllegalStateException("candidate is not held by this delivery lease");
        }
    }

    @Override
    public void markFailed(long id, String leaseToken, String failureType) {
        int changed = jdbc.update("UPDATE gap_issue_candidate SET status='FAILED',failure_type=?,"
                        + "sending_started_at=NULL,lease_token=NULL,updated_at=? WHERE tenant_id=0 "
                        + "AND id=? AND status='SENDING' AND lease_token=?",
                failureType,
                LocalDateTime.now(ZoneOffset.UTC),
                id,
                leaseToken);
        if (changed != 1) {
            throw new IllegalStateException("candidate is not held by this delivery lease");
        }
    }

    private Optional<GapIssueCandidate> findByKeyForUpdate(String key) {
        return jdbc.query("SELECT * FROM gap_issue_candidate WHERE tenant_id=0 AND idempotency_key=? FOR UPDATE",
                        (rs, rowNum) -> map(rs),
                        key)
                .stream()
                .findFirst();
    }

    private boolean canMergeSamples(GapIssueStatus status) {
        return status == GapIssueStatus.PENDING
                || status == GapIssueStatus.APPROVED
                || status == GapIssueStatus.FAILED;
    }

    private GapIssueCandidate map(ResultSet resultSet) throws SQLException {
        return new GapIssueCandidate(
                resultSet.getLong("id"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("cluster_key"),
                BusinessModule.valueOf(resultSet.getString("business_module")),
                GapSeverity.valueOf(resultSet.getString("severity")),
                resultSet.getString("title"),
                read(resultSet.getString("scenario_samples")),
                resultSet.getString("expected_behavior"),
                resultSet.getString("missing_capability"),
                sourceNos(resultSet.getLong("id")),
                GapIssueStatus.valueOf(resultSet.getString("status")),
                (Long) resultSet.getObject("issue_number"),
                resultSet.getString("issue_url"),
                resultSet.getString("reviewed_by"),
                instant(resultSet.getTimestamp("reviewed_at")),
                resultSet.getString("failure_type"),
                resultSet.getInt("attempt_count"),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at")),
                instant(resultSet.getTimestamp("sending_started_at")));
    }

    private Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private List<String> sourceNos(long candidateId) {
        return jdbc.query("SELECT gap_no FROM gap_issue_source WHERE tenant_id=0 AND candidate_id=? ORDER BY gap_no",
                (rs, rowNum) -> rs.getString(1),
                candidateId);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize candidate data", exception);
        }
    }

    private List<String> read(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("cannot deserialize candidate data", exception);
        }
    }
}
