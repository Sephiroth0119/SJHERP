package com.sjherp.infra.persistence.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;

class JdbcConsistencyCheckRunRepositoryTest {

    @Test
    void leavesRunUnassignedWhenFindingBatchInsertFails() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("simulated failure");
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
            invocation.<KeyHolder>getArgument(1).getKeyList().add(Map.of("id", 42L));
            return 1;
        });
        when(jdbc.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenThrow(failure);
        ConsistencyCheckRun run = runWithFinding();

        assertThatThrownBy(() -> new JdbcConsistencyCheckRunRepository(jdbc).save(run)).isSameAs(failure);

        assertThat(run.id()).isNull();
    }

    private static ConsistencyCheckRun runWithFinding() {
        Instant startedAt = Instant.parse("2026-07-19T00:00:00Z");
        return ConsistencyCheckRun.completed(0, "CHK-UNIT-0001", ConsistencyCheckRun.TriggerType.MANUAL_API,
                "tester", startedAt, startedAt.plusSeconds(1),
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null,
                List.of(new ConsistencyFinding(1, "IT-RULE", "SQL_ASSERTION", "test-object",
                        new BigDecimal("1.000000"), BigDecimal.ZERO,
                        ConsistencyFinding.Severity.ERROR, "batch failure")));
    }
}
